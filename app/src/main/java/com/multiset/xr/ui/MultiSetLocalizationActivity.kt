/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.camera.ImageProcessor
import com.multiset.sdk.model.FalsePositiveCause
import com.multiset.sdk.model.FalsePositiveInfo
import com.multiset.sdk.model.GateRejection
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.model.LocalizationStage
import com.multiset.sdk.model.RecoveryPhase
import com.multiset.sdk.session.LocalizationSession
import com.multiset.sdk.source.GeoHintProvider
import com.multiset.sdk.ui.MultiSetWatermark
import com.multiset.xr.R
import com.multiset.xr.ar.ArFrameSource
import com.multiset.xr.ar.GizmoNode
import com.multiset.xr.ar.MapMeshLoads
import com.multiset.xr.ar.MeshRenderer
import com.multiset.xr.ar.reportArTracking
import com.multiset.xr.config.LocalizationConfig
import com.multiset.xr.databinding.ActivityLocalizationBinding
import com.multiset.xr.mapping.AndroidLocationProvider
import com.multiset.xr.ui.runstatus.RunStatusCopy
import com.multiset.xr.ui.runstatus.RunStatusIndicator
import com.multiset.xr.ui.runstatus.RunStatusState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.multiset.sdk.model.TrackingState as SdkTrackingState

class MultiSetLocalizationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MultiSetLocalizationActivity"
        const val EXTRA_LOCALIZATION_MODE = "localization_mode"
        private const val GPS_REFRESH_MS = 1_000L

        // A geo hint narrows the search to where the fix says; one older than this is not sent.
        private const val GEO_HINT_MAX_AGE_MS = 120_000L
    }

    private lateinit var binding: ActivityLocalizationBinding
    private lateinit var arFragment: ArFragment

    private var localizationSession: LocalizationSession? = null
    private var frameSource: ArFrameSource? = null
    private val failureAlert = FailureAlert(com.multiset.xr.R.string.error_title_localization)
    private var gizmoNode: GizmoNode? = null
    private var phoneAnimator: ObjectAnimator? = null
    private var meshRenderer: MeshRenderer? = null
    private val mapMeshes = MapMeshLoads()
    private val meshLoadJobs = mutableMapOf<String, Job>()
    private var meshToggle: MeshToggle? = null
    private var runStatus: RunStatusIndicator? = null
    private var locationProvider: AndroidLocationProvider? = null
    private var geoHintProvider: GeoHintProvider? = null

    private var isSessionConfigured = false
    private var sessionStarted = false
    private var pendingAutoStart = false
    // Null until the first frame, so the session hears the first state too
    private var lastTrackingState: TrackingState? = null
    private var lastFailureReason: TrackingFailureReason? = null
    private var isGizmoPlaced = false
    private var isMapOutOfPlace = false
    private var isOverlayShown = false
    private var localizationMode: LocalizationMode = MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE
    private var watermarkClearance = 0

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locationProvider?.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLocalizationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChromeInsets()

        localizationMode = try {
            LocalizationMode.valueOf(
                intent.getStringExtra(EXTRA_LOCALIZATION_MODE) ?: MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE.name
            )
        } catch (e: IllegalArgumentException) {
            MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE
        }

        Log.d(TAG, "MultiSetLocalizationActivity started in $localizationMode mode")

        setupAR()
        setupUI()
        setupGeoHint()
    }


    private fun setupAR() {
        val fragment = supportFragmentManager.findFragmentById(binding.arFragment.id)
        if (fragment !is ArFragment) {
            Log.e(TAG, "ArFragment not found")
            finish()
            return
        }
        arFragment = fragment

        arFragment.viewLifecycleOwnerLiveData.observe(this) { owner ->
            if (owner != null && arFragment.arSceneView != null) {
                arFragment.arSceneView.scene.addOnUpdateListener {
                    onSceneUpdate()
                    if (!isSessionConfigured) {
                        arFragment.arSceneView.session?.let { arcoreSession ->
                            isSessionConfigured = true
                            configureSession(arcoreSession)
                            buildAndStartSession()
                        }
                    }
                }
            }
        }
    }

    private fun configureSession(session: Session) {
        val config = Config(session).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = Config.DepthMode.DISABLED
            planeFindingMode = Config.PlaneFindingMode.DISABLED
        }
        session.configure(config)

        arFragment.arSceneView.planeRenderer.isEnabled = false
        arFragment.arSceneView.planeRenderer.isVisible = false

        hideInstructionsView()
        setupSceneLighting()
    }

    private fun setupSceneLighting() {
        val scene = arFragment.arSceneView.scene

        val sunLight = Light.builder(Light.Type.DIRECTIONAL)
            .setColor(Color(1.0f, 1.0f, 1.0f))
            .setIntensity(100000f)
            .setShadowCastingEnabled(false)
            .build()
        val sunLightNode = Node().apply {
            light = sunLight
            localPosition = Vector3(0f, 10f, 0f)
            localRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), -45f)
        }
        scene.addChild(sunLightNode)

        val fillLight = Light.builder(Light.Type.DIRECTIONAL)
            .setColor(Color(0.9f, 0.9f, 1.0f))
            .setIntensity(50000f)
            .setShadowCastingEnabled(false)
            .build()
        val fillLightNode = Node().apply {
            light = fillLight
            localPosition = Vector3(0f, 5f, -5f)
            localRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), 45f)
        }
        scene.addChild(fillLightNode)
    }


    private fun buildAndStartSession() {
        val source = ArFrameSource(arFragment, this, ImageProcessor()).also { frameSource = it }

        gizmoNode = GizmoNode(this)
        arFragment.arSceneView.scene.addChild(gizmoNode)
        // Stays hidden until a pose exists; otherwise it floats in front of the camera at origin.
        gizmoNode?.hide()

        meshRenderer = MeshRenderer(this)

        val session = MultiSetSDK.localizationSession(source, localizationMode).apply {
            backgroundLocalization = LocalizationConfig.backgroundLocalization
            bgLocalizationDurationMs = (LocalizationConfig.backgroundLocalizationIntervalSeconds * 1000).toLong()
            relocalization = LocalizationConfig.relocalization
            numberOfFrames = LocalizationConfig.numberOfFrames
            frameCaptureIntervalMs = LocalizationConfig.frameCaptureIntervalMs
            confidenceCheck = LocalizationConfig.confidenceCheck
            confidenceThreshold = LocalizationConfig.confidenceThreshold
            firstLocalizationUntilSuccess = LocalizationConfig.firstLocalizationUntilSuccess
            imageQuality = LocalizationConfig.imageQuality
            queryMode = LocalizationConfig.queryMode
            poseConsistencyCheck = LocalizationConfig.poseConsistencyCheck
            poseConsistencyThreshold = LocalizationConfig.poseConsistencyThreshold
            poseConsistencyYawThreshold = LocalizationConfig.poseConsistencyYawThreshold
            hintMapCodes = LocalizationConfig.hintMapCodes
            hintPosition = LocalizationConfig.hintPosition
            hintFloorHeight = LocalizationConfig.hintFloorHeight
            hintRadius = LocalizationConfig.hintRadius
            use2DFiltering = LocalizationConfig.use2DFiltering
            enableGeoHint = LocalizationConfig.enableGeoHint
            includeGeoCoordinatesInResponse = LocalizationConfig.includeGeoCoordinatesInResponse

            onLocalizationRequested = {
                runOnUiThread {
                    binding.statusText.text = getString(R.string.localizing_status)
                    binding.statusOverlay.visibility = View.VISIBLE
                }
            }

            onStageChanged = { stage, isBackground ->
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) renderRun(stage, isBackground)
                }
            }

            onRecoveryPhaseChanged = { phase, needsRealignment ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    isMapOutOfPlace = needsRealignment
                    applyGizmoVisibility()
                    when (phase) {
                        RecoveryPhase.WAITING_FOR_TRACKING -> R.string.recovery_waiting
                        RecoveryPhase.REALIGNING -> R.string.recovery_realigning
                        RecoveryPhase.NONE -> null
                    }?.let {
                        binding.statusText.text = getString(it)
                        binding.statusOverlay.visibility = View.VISIBLE
                    }
                }
            }

            onLocalizationSuccess = { result ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    runStatus?.receive(RunStatusState.Succeeded)

                    val pos = result.position
                    val rot = result.rotation
                    gizmoNode?.let { node ->
                        node.localPosition = Vector3(pos[0], pos[1], pos[2])
                        node.localRotation = Quaternion(rot[0], rot[1], rot[2], rot[3])
                    }
                    isGizmoPlaced = true
                    applyGizmoVisibility()

                    binding.statusText.text = getString(R.string.localized_map, result.mapCode)
                    binding.statusOverlay.visibility = View.VISIBLE
                    binding.resetButton.visibility = View.VISIBLE

                    if (LocalizationConfig.showAlerts) {
                        Toast.makeText(
                            this@MultiSetLocalizationActivity,
                            getString(com.multiset.xr.R.string.localization_success),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    Log.d(TAG, "Localization success — map: ${result.mapCode}, confidence: ${result.confidence}")
                    loadMapMesh(result.mapCode)
                }
            }

            onLocalizationFalsePositive = { info ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    runStatus?.receive(RunStatusState.Failed)

                    binding.statusText.text = getString(R.string.false_positive_status)
                    binding.statusOverlay.visibility = View.VISIBLE

                    Log.w(TAG, "Discarded ${info.summary}")
                    if (LocalizationConfig.showAlerts) showFalsePositiveDialog(info)
                }
            }

            onLocalizationCorrected = { from, to, trust ->
                val moved = from.position.let { a ->
                    val dx = to.position.x - a.x; val dy = to.position.y - a.y; val dz = to.position.z - a.z
                    kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                }
                Log.w(TAG, "Map anchor displaced by $moved m, now $trust")
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    if (LocalizationConfig.showAlerts) {
                        Toast.makeText(
                            this@MultiSetLocalizationActivity,
                            getString(R.string.localization_corrected, moved),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            onLocalizationFailure = { error ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    runStatus?.receive(RunStatusState.Failed)

                    binding.statusText.text = getString(R.string.localization_failed)
                    binding.statusOverlay.visibility = View.VISIBLE

                    if (error.isTransient) {
                        if (LocalizationConfig.showAlerts) {
                            Toast.makeText(
                                this@MultiSetLocalizationActivity,
                                getString(com.multiset.xr.R.string.localization_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        failureAlert.show(this@MultiSetLocalizationActivity, error)
                    }

                    Log.e(TAG, "Localization failure: ${error.kind} — ${error.message}")
                }
            }
        }
        localizationSession = session

        // Defer auto-start until ARCore is actually TRACKING (see onSceneUpdate).
        // Starting capture before tracking causes "Failed to capture frame".
        pendingAutoStart = LocalizationConfig.autoLocalize
    }

    /**
     * A run the user is waiting on covers the scene: the capture overlay while frames are taken,
     * then the server wait. A background refresh or a recovery shows only on the status card.
     */
    private fun renderRun(stage: LocalizationStage, isBackground: Boolean) {
        val session = localizationSession ?: return
        val awaitingRetry = session.isAwaitingRetry
        if (stage == LocalizationStage.Idle && awaitingRetry) {
            runStatus?.keepOpen()
        } else {
            runStatus?.receive(RunStatusState.from(stage))
        }

        isOverlayShown = (stage != LocalizationStage.Idle && !isBackground) || awaitingRetry
        val capturing = isOverlayShown && stage is LocalizationStage.Capturing
        binding.captureOverlay.isVisible = capturing
        binding.serverWaitOverlay.isVisible = isOverlayShown && !capturing
        if (capturing) {
            binding.captureStatusText.text = RunStatusCopy.captureStatus(stage)
            phoneAnimator?.takeUnless { it.isStarted }?.start()
        } else {
            phoneAnimator?.cancel()
            binding.serverWaitText.text = RunStatusCopy.serverWaitText(stage, objectTracking = false)
        }
        updateLocalizeButton()
    }

    private fun updateLocalizeButton() {
        val session = localizationSession
        binding.localizeButton.isVisible = !isOverlayShown
        val enabled = lastTrackingState == TrackingState.TRACKING && session != null && !session.isBusy
        binding.localizeButton.isEnabled = enabled
        binding.localizeButton.alpha = if (enabled) 1f else 0.5f
    }

    /** The gizmo, and the map mesh under it, show once placed and while the scene is where the room is. */
    private fun applyGizmoVisibility() {
        val node = gizmoNode ?: return
        if (isGizmoPlaced && !isMapOutOfPlace) node.show() else node.hide()
    }

    /**
     * A fix on a map whose mesh is in the scene only restarts its reveal. A fix on another map of a
     * map set adds that map's mesh; the meshes already shown or loading stay.
     */
    private fun loadMapMesh(mapCode: String) {
        if (!LocalizationConfig.enableMeshVisualization) return
        val camera = arFragment.arSceneView.scene.camera.worldPosition
        val load = when (val action = mapMeshes.onFix(mapCode)) {
            is MapMeshLoads.Action.RestartReveal -> {
                meshRenderer?.restartReveal(action.mapId, camera)
                return
            }
            MapMeshLoads.Action.AlreadyLoading -> return
            is MapMeshLoads.Action.Load -> action
        }

        meshLoadJobs[mapCode] = lifecycleScope.launch {
            var rendering = false
            try {
                val res = MultiSetSDK.meshRepository().loadMapMesh(mapCode) ?: return@launch
                if (isDestroyed || isFinishing) return@launch
                val node = gizmoNode ?: return@launch
                val renderer = meshRenderer ?: return@launch
                rendering = true
                renderer.renderMesh(res, node, arFragment.arSceneView.scene.camera.worldPosition) { success ->
                    mapMeshes.onLoaded(mapCode, load.ticket, res.mapId.takeIf { success })
                    Log.d(TAG, "Map mesh render result: $success (mapId=${res.mapId})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load map mesh: ${e.message}", e)
            } finally {
                if (!rendering) mapMeshes.onLoaded(mapCode, load.ticket, null)
            }
        }
    }

    private fun dropMapMeshes() {
        meshLoadJobs.values.forEach { it.cancel() }
        meshLoadJobs.clear()
        mapMeshes.clear()
    }


    private fun onSceneUpdate() {
        val meshLoading = mapMeshes.isLoading || meshRenderer?.isLoading == true
        binding.meshLoadingBar.isVisible = meshLoading
        meshToggle?.setAvailable(!meshLoading && meshRenderer?.hasMesh() == true)

        val frame = arFragment.arSceneView.arFrame ?: return
        val currentState = frame.camera.trackingState

        // A stale camera pose would re-centre the reveal somewhere the user no longer is.
        meshRenderer?.onFrame(
            if (currentState == TrackingState.TRACKING) arFragment.arSceneView.scene.camera.worldPosition else null
        )

        // Auto-start localization only once ARCore reaches TRACKING (not on first configure).
        if (currentState == TrackingState.TRACKING && pendingAutoStart && !sessionStarted) {
            pendingAutoStart = false
            sessionStarted = true
            Log.d(TAG, "AR tracking established — starting localization")
            localizationSession?.start()
        }

        val reason = frame.camera.trackingFailureReason
        // A reason can change while the state does not, e.g. poor features turning into a lost camera.
        if (currentState != lastTrackingState || reason != lastFailureReason) {
            lastTrackingState = currentState
            lastFailureReason = reason
            // The SDK re-localizes once tracking returns after a break that follows a fix.
            localizationSession?.reportArTracking(currentState, reason)
            updateLocalizeButton()
        }
    }

    /** Mirrors the iOS demo app's false-positive prompt. */
    private fun showFalsePositiveDialog(info: FalsePositiveInfo) {
        val body = when (info.cause) {
            FalsePositiveCause.ANCHOR_HELD_VOTE -> getString(
                R.string.false_positive_body_held,
                info.jumpMeters, info.yawDeltaDeg, info.thresholdMeters, info.thresholdDegrees,
            )
            FalsePositiveCause.INLIER_MARGIN -> getString(
                R.string.false_positive_body_aliasing,
                info.jumpMeters, info.yawDeltaDeg, info.thresholdMeters, info.thresholdDegrees,
            )
            FalsePositiveCause.INSUFFICIENT_VIEWPOINTS, null -> getString(
                R.string.false_positive_body,
                info.jumpMeters, info.yawDeltaDeg, info.thresholdMeters, info.thresholdDegrees,
                info.challengerSupport, info.requiredSupport,
            )
        }
        val lines = mutableListOf(body)
        if (info.consecutiveCount > 1) {
            lines += getString(R.string.false_positive_streak, info.consecutiveCount)
        }
        // Multi-frame from more angles cannot fix structural aliasing, so do not send the user
        // walking; the reset is the only way out and the hint below says so.
        val aliasing = info.rejection == GateRejection.SUSPECTED_ALIASING
        lines += getString(if (aliasing) R.string.false_positive_aliasing_advice else R.string.false_positive_advice)
        if (aliasing || info.consecutiveCount >= 3) {
            lines += getString(R.string.false_positive_reset_hint)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.false_positive_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.false_positive_dismiss) { d, _ -> d.dismiss() }
            .show()
    }


    /** AR chrome must clear the status bar / gesture handle while the camera stays full-bleed. */
    private fun applyChromeInsets() {
        binding.topLeftColumn.marginForSystemBars(top = true)
        binding.topActions.marginForSystemBars(top = true)
        binding.localizeButton.marginForSystemBars(bottom = true)
        binding.meshToggle.marginForSystemBars(bottom = true) { watermarkClearance }
        binding.meshLoadingBar.marginForSystemBars(bottom = true) { watermarkClearance }

        MultiSetWatermark.attach(this) { clearance ->
            watermarkClearance = clearance
            ViewCompat.requestApplyInsets(binding.meshToggle)
            ViewCompat.requestApplyInsets(binding.meshLoadingBar)
        }
    }

    private fun setupUI() {
        setupPhoneAnimation()
        binding.meshLoadingBar.setLabel(getString(R.string.mesh_loading_map))
        runStatus = RunStatusIndicator { binding.runStatusCard.setState(it) }

        meshToggle = MeshToggle(binding.meshToggle) { visible ->
            meshRenderer?.setMeshVisible(visible)
        }

        binding.localizeButton.setOnClickListener {
            localizationSession?.let {
                failureAlert.reset()
                it.start()
                sessionStarted = true
            }
        }

        binding.resetButton.setOnClickListener {
            failureAlert.reset()
            // Unlike resetPoseReference(), this re-arms the first-localization latch too.
            localizationSession?.resetSession()
            phoneAnimator?.cancel()
            dropMapMeshes()
            // Hiding the gizmo takes the meshes off screen with it, but the renderer would keep
            // holding those models and hand them straight back when their maps are localized next.
            meshRenderer?.removeMesh()
            gizmoNode?.let {
                it.localPosition = Vector3.zero()
                it.localRotation = Quaternion.identity()
            }
            isGizmoPlaced = false
            isMapOutOfPlace = false
            applyGizmoVisibility()
            binding.resetButton.visibility = View.GONE
            binding.statusText.text = getString(R.string.initializing_status)

            // Re-arm the same gate the initial start uses instead of calling start() here:
            // capture before ARCore is TRACKING fails with "Failed to capture frame".
            sessionStarted = false
            pendingAutoStart = LocalizationConfig.autoLocalize
        }

        binding.closeButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.close_localization_title)
                .setMessage(R.string.close_scene_body)
                .setPositiveButton(R.string.close_confirm) { _, _ -> finish() }
                .setNegativeButton(R.string.close_cancel) { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    /** The GPS hint needs a location permission; the indicator says whether a fix is going out. */
    private fun setupGeoHint() {
        if (!LocalizationConfig.enableGeoHint) return
        val provider = AndroidLocationProvider(applicationContext).also { locationProvider = it }
        geoHintProvider = GeoHintProvider { provider.currentFix(GEO_HINT_MAX_AGE_MS) }.also { MultiSetSDK.geoHintProvider = it }
        binding.gpsIndicator.visibility = View.VISIBLE

        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (permissions.none { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            locationPermission.launch(permissions)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val hasFix = provider.currentFix(GEO_HINT_MAX_AGE_MS)?.isValid() == true
                    binding.gpsStatusDot.setBackgroundResource(if (hasFix) R.drawable.gps_dot_active else R.drawable.gps_dot_inactive)
                    delay(GPS_REFRESH_MS)
                }
            }
        }
    }

    private fun setupPhoneAnimation() {
        phoneAnimator = ObjectAnimator.ofFloat(binding.phoneImage, "translationX", 0f, 260f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }


    private fun hideInstructionsView() {
        arFragment.view?.let { fragmentView ->
            if (fragmentView is android.view.ViewGroup) {
                hideInstructionsRecursively(fragmentView)
            }
        }
    }

    private fun hideInstructionsRecursively(viewGroup: android.view.ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            val resourceName = try {
                resources.getResourceEntryName(child.id)
            } catch (e: Exception) { "" }
            if (resourceName.contains("instruction", ignoreCase = true) ||
                resourceName.contains("hand", ignoreCase = true)) {
                child.visibility = View.GONE
            }
            if (child is android.view.ViewGroup) hideInstructionsRecursively(child)
        }
    }


    override fun onResume() {
        super.onResume()
        locationProvider?.start()
    }

    /**
     * ARCore cannot reset its world frame, and one resumed after a trip to the background may no
     * longer match the room, so the fix is treated as stale: the map hides and the SDK re-localizes
     * once tracking is back. Ahead of super.onPause(), which pauses the AR session.
     */
    override fun onPause() {
        frameSource?.markFramesStale()
        if (sessionStarted) {
            localizationSession?.reportTrackingState(SdkTrackingState.PAUSED)
            localizationSession?.notifyWorldOriginReset()
        }
        // Reported again on the first frame after the return, even if ARCore is tracking at once.
        lastTrackingState = TrackingState.PAUSED
        lastFailureReason = null
        locationProvider?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        failureAlert.dismiss()
        phoneAnimator?.cancel()
        runStatus?.release()
        localizationSession?.stop()
        localizationSession = null
        dropMapMeshes()
        gizmoNode = null
        meshRenderer?.release()
        meshRenderer = null
        locationProvider?.stop()
        if (MultiSetSDK.geoHintProvider === geoHintProvider) MultiSetSDK.geoHintProvider = null
    }
}
