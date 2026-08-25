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
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.xr.ar.ArFrameSource
import com.multiset.xr.ar.GizmoNode
import com.multiset.xr.ar.MeshRenderer
import com.multiset.xr.R
import com.multiset.sdk.model.FalsePositiveInfo
import com.multiset.xr.config.LocalizationConfig
import com.multiset.xr.databinding.ActivityLocalizationBinding
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.camera.ImageProcessor
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.session.LocalizationSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MultiSetLocalizationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MultiSetLocalizationActivity"
        const val EXTRA_LOCALIZATION_MODE = "localization_mode"
    }

    private lateinit var binding: ActivityLocalizationBinding
    private lateinit var arFragment: ArFragment

    private var localizationSession: LocalizationSession? = null
    private val failureAlert = FailureAlert(com.multiset.xr.R.string.error_title_localization)
    private var gizmoNode: GizmoNode? = null
    private var phoneAnimator: ObjectAnimator? = null
    private var meshRenderer: MeshRenderer? = null
    private var meshLoadJob: Job? = null

    private var isSessionConfigured = false
    private var sessionStarted = false
    private var pendingAutoStart = false
    private var lastTrackingState = TrackingState.TRACKING
    private var localizationMode: LocalizationMode = LocalizationMode.MULTI_FRAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLocalizationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChromeInsets()

        localizationMode = try {
            LocalizationMode.valueOf(
                intent.getStringExtra(EXTRA_LOCALIZATION_MODE) ?: LocalizationMode.MULTI_FRAME.name
            )
        } catch (e: IllegalArgumentException) {
            LocalizationMode.MULTI_FRAME
        }

        Log.d(TAG, "MultiSetLocalizationActivity started in $localizationMode mode")

        setupAR()
        setupUI()
    }

    // ── AR setup ──────────────────────────────────────────────────────────────

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
            depthMode = Config.DepthMode.AUTOMATIC
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

    // ── Session wiring ────────────────────────────────────────────────────────

    private fun buildAndStartSession() {
        val frameSource = ArFrameSource(arFragment, this, ImageProcessor())

        gizmoNode = GizmoNode(this)
        arFragment.arSceneView.scene.addChild(gizmoNode)
        // Stays hidden until a pose exists; otherwise it floats in front of the camera at origin.
        gizmoNode?.hide()

        meshRenderer = MeshRenderer(this).apply {
            // Matches reference: provide the live camera world position only while tracking,
            // otherwise null so the reveal animation skips re-centering on a stale pose.
            setCameraPositionProvider {
                val f = arFragment.arSceneView.arFrame
                if (f != null && f.camera.trackingState == TrackingState.TRACKING)
                    arFragment.arSceneView.scene.camera.worldPosition
                else null
            }
        }

        val session = MultiSetSDK.localizationSession(frameSource, localizationMode).apply {
            backgroundLocalization = LocalizationConfig.backgroundLocalization
            bgLocalizationDurationMs = (LocalizationConfig.backgroundLocalizationIntervalSeconds * 1000).toLong()
            numberOfFrames = LocalizationConfig.numberOfFrames
            frameCaptureIntervalMs = LocalizationConfig.frameCaptureIntervalMs
            confidenceCheck = LocalizationConfig.confidenceCheck
            confidenceThreshold = LocalizationConfig.confidenceThreshold
            firstLocalizationUntilSuccess = LocalizationConfig.firstLocalizationUntilSuccess
            imageQuality = LocalizationConfig.imageQuality
            queryMode = LocalizationConfig.queryMode
            poseConsistencyCheck = LocalizationConfig.poseConsistencyCheck
            poseConsistencyThreshold = LocalizationConfig.poseConsistencyThreshold
            hintMapCodes = LocalizationConfig.hintMapCodes

            onLocalizationRequested = {
                runOnUiThread {
                    showScanOverlay()
                    phoneAnimator?.start()
                    binding.statusText.text = getString(R.string.localizing_status)
                    binding.statusOverlay.visibility = View.VISIBLE
                }
            }

            onLocalizationSuccess = { result ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    phoneAnimator?.cancel()
                    hideAllOverlays()

                    val pos = result.position
                    val rot = result.rotation
                    val position = Vector3(pos[0], pos[1], pos[2])
                    val rotation = Quaternion(rot[0], rot[1], rot[2], rot[3])

                    gizmoNode?.let { node ->
                        node.localPosition = position
                        node.localRotation = rotation
                        node.show()
                    }

                    binding.statusText.text = getString(R.string.localized_map, result.mapCode)
                    binding.localizationStatus.text = getString(com.multiset.xr.R.string.ready_to_localize)
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

                    if (LocalizationConfig.enableMeshVisualization) {
                        meshLoadJob = lifecycleScope.launch {
                            try {
                                val res = MultiSetSDK.meshRepository().loadMapMesh(result.mapCode)
                                    ?: return@launch
                                runOnUiThread {
                                    if (isDestroyed || isFinishing) return@runOnUiThread
                                    val node = gizmoNode ?: return@runOnUiThread
                                    meshRenderer?.renderMesh(
                                        res,
                                        node,
                                        arFragment.arSceneView.scene.camera.worldPosition
                                    ) { success ->
                                        Log.d(TAG, "Map mesh render result: $success (mapId=${res.mapId})")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load map mesh: ${e.message}", e)
                            }
                        }
                    }
                }
            }

            onLocalizationFalsePositive = { info ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    phoneAnimator?.cancel()
                    hideAllOverlays()

                    binding.statusText.text = getString(R.string.false_positive_status)
                    binding.statusOverlay.visibility = View.VISIBLE

                    Log.w(TAG, "Discarded false positive: ${info.summary}")
                    if (LocalizationConfig.showAlerts) showFalsePositiveDialog(info)
                }
            }

            onLocalizationFailure = { error ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    phoneAnimator?.cancel()
                    hideAllOverlays()

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

    // ── Scene update — relocalization on tracking loss ────────────────────────

    private fun onSceneUpdate() {
        val frame = arFragment.arSceneView.arFrame ?: return
        val currentState = frame.camera.trackingState

        // Auto-start localization only once ARCore reaches TRACKING (not on first configure).
        if (currentState == TrackingState.TRACKING && pendingAutoStart && !sessionStarted) {
            pendingAutoStart = false
            sessionStarted = true
            Log.d(TAG, "AR tracking established — starting localization")
            localizationSession?.start()
        }

        if (currentState != lastTrackingState) {
            val previous = lastTrackingState
            lastTrackingState = currentState

            if (previous == TrackingState.TRACKING
                && currentState != TrackingState.TRACKING
                && sessionStarted
            ) {
                // The pose gate must learn the tracker broke even when auto-relocalization is
                // off, or it keeps validating against a reference the tracker no longer backs.
                localizationSession?.notifyTrackingInterrupted()

                if (LocalizationConfig.relocalization) {
                    Log.d(TAG, "AR tracking lost (→ $currentState) — triggering relocalization")
                    localizationSession?.stop()
                    localizationSession?.start()
                }
            }
        }
    }

    /** Mirrors the iOS demo app's false-positive prompt. */
    private fun showFalsePositiveDialog(info: FalsePositiveInfo) {
        val lines = mutableListOf(
            getString(R.string.false_positive_body, info.jumpMeters, info.thresholdMeters)
        )
        if (info.consecutiveCount > 1) {
            lines += getString(R.string.false_positive_streak, info.consecutiveCount)
        }
        lines += getString(R.string.false_positive_advice)
        // Nothing overrules a reference the device still vouches for, so after a run of
        // rejections the user needs to know how to start over — the reference may be the wrong one.
        if (info.consecutiveCount >= 3) {
            lines += getString(R.string.false_positive_reset_hint)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.false_positive_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.false_positive_dismiss) { d, _ -> d.dismiss() }
            .show()
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    /** AR chrome must clear the status bar / gesture handle while the camera stays full-bleed. */
    private fun applyChromeInsets() {
        binding.statusOverlay.marginForSystemBars(top = true)
        binding.closeButton.marginForSystemBars(top = true)
        binding.localizeButton.marginForSystemBars(bottom = true)
        binding.resetButton.marginForSystemBars(bottom = true)
        binding.backgroundProgressIndicator.marginForSystemBars(bottom = true)
    }

    private fun setupUI() {
        setupPhoneAnimation()

        binding.localizeButton.setOnClickListener {
            localizationSession?.let {
                failureAlert.reset()
                it.start()
                sessionStarted = true
            }
        }

        binding.resetButton.setOnClickListener {
            failureAlert.reset()
            localizationSession?.stop()
            // Reset puts the scene back to square one, so the next fix is the new reference.
            localizationSession?.resetPoseReference()
            phoneAnimator?.cancel()
            hideAllOverlays()
            meshLoadJob?.cancel()
            meshLoadJob = null
            // Hiding the gizmo takes the mesh off screen with it, but the renderer would keep
            // holding that model and hand it straight back for whatever map is localized next.
            meshRenderer?.removeMesh()
            gizmoNode?.let {
                it.localPosition = Vector3.zero()
                it.localRotation = Quaternion.identity()
                it.hide()
            }
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

        // GPS indicator hidden — GPS wiring not available in this app module
        binding.gpsIndicator.visibility = View.GONE
    }

    private fun setupPhoneAnimation() {
        phoneAnimator = ObjectAnimator.ofFloat(binding.phoneImage, "translationX", 0f, 260f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    // ── Overlay helpers ───────────────────────────────────────────────────────

    private fun showScanOverlay() {
        binding.localizeButton.visibility = View.GONE
        binding.backgroundProgressIndicator.visibility = View.GONE
        when (localizationMode) {
            LocalizationMode.SINGLE_FRAME -> {
                binding.singleFrameOverlay.visibility = View.VISIBLE
                binding.multiFrameOverlay.visibility = View.GONE
            }
            LocalizationMode.MULTI_FRAME -> {
                binding.multiFrameOverlay.visibility = View.VISIBLE
                binding.singleFrameOverlay.visibility = View.GONE
            }
        }
        binding.apiLoadingOverlay.visibility = View.GONE
    }

    private fun hideAllOverlays() {
        binding.singleFrameOverlay.visibility = View.GONE
        binding.multiFrameOverlay.visibility = View.GONE
        binding.apiLoadingOverlay.visibility = View.GONE
        binding.backgroundProgressIndicator.visibility = View.GONE
        binding.localizeButton.visibility = View.VISIBLE
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        failureAlert.dismiss()
        phoneAnimator?.cancel()
        localizationSession?.stop()
        localizationSession = null
        meshLoadJob?.cancel()
        meshLoadJob = null
        gizmoNode = null
        meshRenderer?.removeMesh()
        meshRenderer = null
    }
}
