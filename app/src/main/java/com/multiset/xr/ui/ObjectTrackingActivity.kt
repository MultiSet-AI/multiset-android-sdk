/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.xr.ar.ArFrameSource
import com.multiset.xr.ar.ObjectMeshRenderer
import com.multiset.xr.ar.reportArTracking
import com.multiset.xr.R
import com.multiset.xr.config.ObjectTrackingConfig
import com.multiset.xr.databinding.ActivityObjectTrackingBinding
import com.multiset.xr.ui.runstatus.RunStatusCopy
import com.multiset.xr.ui.runstatus.RunStatusIndicator
import com.multiset.xr.ui.runstatus.RunStatusState
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.camera.ImageProcessor
import com.multiset.sdk.model.LocalizationStage
import com.multiset.sdk.session.ObjectTrackingSession
import com.multiset.sdk.ui.MultiSetWatermark
import com.multiset.sdk.model.TrackingState as SdkTrackingState

class ObjectTrackingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ObjectTrackingActivity"
        const val EXTRA_OBJECT_CODES = "object_codes"
    }

    private lateinit var binding: ActivityObjectTrackingBinding
    private lateinit var arFragment: ArFragment

    private var intentObjectCodes: List<String> = emptyList()
    private var trackingSession: ObjectTrackingSession? = null
    private var frameSource: ArFrameSource? = null
    private val failureAlert = FailureAlert(com.multiset.xr.R.string.error_title_object_tracking)
    private var meshRenderer: ObjectMeshRenderer? = null

    /** One invisible anchor per object: a result moves only its own object's outline. */
    private val objectAnchors = mutableMapOf<String, Node>()
    private var phoneAnimator: ObjectAnimator? = null
    private var meshToggle: MeshToggle? = null
    private var runStatus: RunStatusIndicator? = null

    private var isSessionConfigured = false
    private var sessionStarted = false
    private var pendingAutoStart = false
    // Null until the first frame, so the session hears the first state too
    private var lastTrackingState: TrackingState? = null
    private var lastFailureReason: TrackingFailureReason? = null
    private var isOverlayShown = false
    private var watermarkClearance = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityObjectTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChromeInsets()

        intentObjectCodes = intent.getStringArrayExtra(EXTRA_OBJECT_CODES)?.toList()
            ?: ObjectTrackingConfig.objectCodes.toList()
        Log.d(TAG, "ObjectTrackingActivity started with ${intentObjectCodes.size} object codes: $intentObjectCodes")

        setupAR()
        setupUI()
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

        meshRenderer = ObjectMeshRenderer(
            context = this,
            scope = lifecycleScope,
            meshRepository = MultiSetSDK.meshRepository(),
        )
        meshRenderer?.loadMaterials()
        // Sceneform draws the camera feed last, where it fails the occluder's depth test and shows black.
        arFragment.arSceneView.cameraStream?.renderPriority = Renderable.RENDER_PRIORITY_FIRST

        val session = (if (intentObjectCodes.isNotEmpty())
            MultiSetSDK.objectTrackingSession(source, intentObjectCodes)
        else
            MultiSetSDK.objectTrackingSession(source)).apply {
            captureDelayMs = ObjectTrackingConfig.captureDelayMs
            backgroundTracking = ObjectTrackingConfig.backgroundTracking
            bgTrackingDurationMs = (ObjectTrackingConfig.bgTrackingDurationSeconds * 1000).toLong()
            restartTracking = ObjectTrackingConfig.restartTracking
            confidenceCheck = ObjectTrackingConfig.confidenceCheck
            confidenceThreshold = ObjectTrackingConfig.confidenceThreshold
            firstTrackingUntilSuccess = ObjectTrackingConfig.firstTrackingUntilSuccess
            imageQuality = ObjectTrackingConfig.imageQuality

            onStageChanged = { stage, isBackground ->
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) renderRun(stage, isBackground)
                }
            }

            onPlacementVisibilityChanged = { objectCode, visible ->
                runOnUiThread { meshRenderer?.setObjectVisible(objectCode, visible) }
            }

            onTrackingSuccess = { result ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    runStatus?.receive(RunStatusState.Succeeded)

                    val pos = result.position
                    val rot = result.rotation
                    val anchor = objectAnchors.getOrPut(result.objectCode) {
                        Node().also { arFragment.arSceneView.scene.addChild(it) }
                    }
                    anchor.localPosition = Vector3(pos[0], pos[1], pos[2])
                    anchor.localRotation = Quaternion(rot[0], rot[1], rot[2], rot[3])
                    meshRenderer?.fetchAndRender(result.objectCode, anchor)

                    // The badge names the object; a status chip saying so too repeated the code.
                    binding.statusOverlay.visibility = View.GONE
                    binding.objectCodeBadge.text = result.objectCode
                    binding.objectCodeBadge.visibility = View.VISIBLE
                    binding.resetButton.visibility = View.VISIBLE

                    if (ObjectTrackingConfig.showAlerts) {
                        Toast.makeText(
                            this@ObjectTrackingActivity,
                            getString(R.string.object_tracked),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    Log.d(TAG, "Object tracking success — code: ${result.objectCode}, confidence: ${result.confidence}")
                }
            }

            onTrackingFailure = { error ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    runStatus?.receive(RunStatusState.Failed)
                    // Once an object is placed the card's red mark says it; the chip would push the badge down.
                    if (!binding.objectCodeBadge.isVisible) {
                        binding.statusText.text = getString(R.string.tracking_failed_short)
                        binding.statusOverlay.visibility = View.VISIBLE
                    }

                    if (error.isTransient) {
                        if (ObjectTrackingConfig.showAlerts) {
                            Toast.makeText(this@ObjectTrackingActivity, "Tracking failed", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        failureAlert.show(this@ObjectTrackingActivity, error)
                    }

                    Log.e(TAG, "Object tracking failure: ${error.kind} — ${error.message}")
                }
            }
        }
        trackingSession = session

        // Defer auto-start until ARCore is actually TRACKING (see onSceneUpdate).
        pendingAutoStart = ObjectTrackingConfig.autoTracking
    }

    /**
     * A run the user is waiting on covers the scene: the capture overlay through the hold and the
     * capture, then the server wait. A background re-track or a restart shows only on the status card.
     */
    private fun renderRun(stage: LocalizationStage, isBackground: Boolean) {
        val session = trackingSession ?: return
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
            binding.serverWaitText.text = RunStatusCopy.serverWaitText(stage, objectTracking = true)
        }
        updateTrackButton()
    }

    private fun updateTrackButton() {
        val session = trackingSession
        binding.trackButton.isVisible = !isOverlayShown
        val enabled = lastTrackingState == TrackingState.TRACKING && session != null && !session.isBusy
        binding.trackButton.isEnabled = enabled
        binding.trackButton.alpha = if (enabled) 1f else 0.5f
    }


    private fun onSceneUpdate() {
        val meshLoading = meshRenderer?.isLoading == true
        binding.meshLoadingBar.isVisible = meshLoading
        meshToggle?.setAvailable(!meshLoading && meshRenderer?.hasMesh() == true)
        meshRenderer?.onFrame()

        val frame = arFragment.arSceneView.arFrame ?: return
        val currentState = frame.camera.trackingState

        // Auto-start tracking only once ARCore reaches TRACKING (not on first configure).
        if (currentState == TrackingState.TRACKING && pendingAutoStart && !sessionStarted) {
            pendingAutoStart = false
            sessionStarted = true
            Log.d(TAG, "AR tracking established — starting object tracking")
            trackingSession?.start()
        }

        val reason = frame.camera.trackingFailureReason
        // A reason can change while the state does not, e.g. poor features turning into a lost camera.
        if (currentState != lastTrackingState || reason != lastFailureReason) {
            lastTrackingState = currentState
            lastFailureReason = reason

            // Surface the AR tracking state in the status overlay (reference UI behaviour)
            binding.statusText.text = getString(
                when (currentState) {
                    TrackingState.TRACKING -> R.string.tracking_normal
                    TrackingState.PAUSED -> R.string.tracking_paused
                    TrackingState.STOPPED -> R.string.tracking_stopped
                }
            )

            // The SDK hides stale outlines and re-tracks once tracking holds again.
            trackingSession?.reportArTracking(currentState, reason)
            updateTrackButton()
        }
    }


    /** AR chrome must clear the status bar / gesture handle while the camera stays full-bleed. */
    private fun applyChromeInsets() {
        binding.topLeftColumn.marginForSystemBars(top = true)
        binding.topActions.marginForSystemBars(top = true)
        binding.trackButton.marginForSystemBars(bottom = true)
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
        binding.meshLoadingBar.setLabel(getString(R.string.mesh_loading_object))
        runStatus = RunStatusIndicator { binding.runStatusCard.setState(it) }

        meshToggle = MeshToggle(binding.meshToggle) { visible ->
            meshRenderer?.setMeshVisible(visible)
        }

        binding.resetButton.setOnClickListener { resetTracking() }

        binding.trackButton.setOnClickListener {
            trackingSession?.let {
                failureAlert.reset()
                // The session reports the capture stage at once and holds captureDelayMs before it captures.
                it.start()
                sessionStarted = true
            }
        }

        binding.closeButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.close_tracking_title)
                .setMessage(R.string.close_scene_body)
                .setPositiveButton(R.string.close_confirm) { _, _ -> finish() }
                .setNegativeButton(R.string.close_cancel) { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    /** Twice the localization pace: one capture follows a one-second hold, and the sweep must finish in it. */
    private fun setupPhoneAnimation() {
        val travel = (binding.animationContainer.layoutParams.width - binding.phoneImage.layoutParams.width).toFloat()
        phoneAnimator = ObjectAnimator.ofFloat(binding.phoneImage, "translationX", 0f, travel).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    /** Clears the outlines and re-arms tracking, the silent first track included. */
    private fun resetTracking() {
        failureAlert.reset()
        trackingSession?.resetSession()
        meshRenderer?.clearMeshes()
        objectAnchors.values.forEach { it.setParent(null) }
        objectAnchors.clear()
        phoneAnimator?.cancel()
        binding.objectCodeBadge.visibility = View.GONE
        binding.resetButton.visibility = View.GONE
        binding.statusOverlay.visibility = View.GONE

        // Re-arm the same gate the initial start uses: capture before ARCore is TRACKING
        // fails with "Failed to capture frame".
        sessionStarted = false
        pendingAutoStart = ObjectTrackingConfig.autoTracking
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


    /**
     * A world frame resumed after a trip to the background may no longer match the room, so every
     * outline is stale until its object is tracked again, which the SDK does once tracking is back.
     * Ahead of super.onPause(), which pauses the AR session.
     */
    override fun onPause() {
        frameSource?.markFramesStale()
        if (sessionStarted) {
            trackingSession?.reportTrackingState(SdkTrackingState.PAUSED)
            trackingSession?.notifyWorldOriginReset()
        }
        // Reported again on the first frame after the return, even if ARCore is tracking at once.
        lastTrackingState = TrackingState.PAUSED
        lastFailureReason = null
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        failureAlert.dismiss()
        phoneAnimator?.cancel()
        runStatus?.release()
        trackingSession?.stop()
        meshRenderer?.release()
        trackingSession = null
        meshRenderer = null
        objectAnchors.clear()
    }
}
