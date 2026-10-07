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
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Session
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
import com.multiset.xr.R
import com.multiset.xr.config.ObjectTrackingConfig
import com.multiset.xr.databinding.ActivityObjectTrackingBinding
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.camera.ImageProcessor
import com.multiset.sdk.session.ObjectTrackingSession
import com.multiset.sdk.ui.MultiSetWatermark

class ObjectTrackingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ObjectTrackingActivity"
        const val EXTRA_OBJECT_CODES = "object_codes"
    }

    private lateinit var binding: ActivityObjectTrackingBinding
    private lateinit var arFragment: ArFragment

    private var intentObjectCodes: List<String> = emptyList()
    private var trackingSession: ObjectTrackingSession? = null
    private val failureAlert = FailureAlert(com.multiset.xr.R.string.error_title_object_tracking)
    private var meshRenderer: ObjectMeshRenderer? = null
    private var objectAnchorNode: Node? = null
    private var phoneAnimator: ObjectAnimator? = null
    private var meshToggle: MeshToggle? = null

    private var isSessionConfigured = false
    private var sessionStarted = false
    private var pendingAutoStart = false
    private var lastTrackingState = TrackingState.TRACKING
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

        meshRenderer = ObjectMeshRenderer(
            context = this,
            scope = lifecycleScope,
            meshRepository = MultiSetSDK.meshRepository(),
        )
        meshRenderer?.loadMaterials()
        // Sceneform draws the camera feed last, where it fails the occluder's depth test and shows black.
        arFragment.arSceneView.cameraStream?.renderPriority = Renderable.RENDER_PRIORITY_FIRST

        // Invisible anchor node for the tracked object's mesh (no gizmo shown in object tracking).
        objectAnchorNode = Node()
        arFragment.arSceneView.scene.addChild(objectAnchorNode)

        val session = (if (intentObjectCodes.isNotEmpty())
            MultiSetSDK.objectTrackingSession(frameSource, intentObjectCodes)
        else
            MultiSetSDK.objectTrackingSession(frameSource)).apply {
            captureDelayMs = ObjectTrackingConfig.captureDelayMs
            backgroundTracking = ObjectTrackingConfig.backgroundTracking
            bgTrackingDurationMs = (ObjectTrackingConfig.bgTrackingDurationSeconds * 1000).toLong()
            confidenceCheck = ObjectTrackingConfig.confidenceCheck
            confidenceThreshold = ObjectTrackingConfig.confidenceThreshold
            firstTrackingUntilSuccess = ObjectTrackingConfig.firstTrackingUntilSuccess
            imageQuality = ObjectTrackingConfig.imageQuality

            onTrackingRequested = {
                runOnUiThread { showTrackingOverlay() }
            }

            onTrackingSuccess = { result ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    phoneAnimator?.cancel()
                    binding.trackingOverlay.visibility = View.GONE
                    binding.trackButton.visibility = View.VISIBLE

                    val pos = result.position
                    val rot = result.rotation
                    val position = Vector3(pos[0], pos[1], pos[2])
                    val rotation = Quaternion(rot[0], rot[1], rot[2], rot[3])

                    objectAnchorNode?.let { node ->
                        node.localPosition = position
                        node.localRotation = rotation
                        meshRenderer?.fetchAndRender(result.objectCode, node)
                    }

                    binding.statusText.text = getString(R.string.tracked_object, result.objectCode)
                    binding.statusOverlay.visibility = View.VISIBLE
                    binding.objectCodeBadge.text = result.objectCode
                    binding.objectCodeBadge.visibility = View.VISIBLE
                    binding.resetButton.visibility = View.VISIBLE

                    if (ObjectTrackingConfig.showAlerts) {
                        Toast.makeText(
                            this@ObjectTrackingActivity,
                            "Object tracked: ${result.objectCode}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    Log.d(TAG, "Object tracking success — code: ${result.objectCode}, confidence: ${result.confidence}")
                }
            }

            onTrackingFailure = { error ->
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    phoneAnimator?.cancel()
                    binding.trackingOverlay.visibility = View.GONE
                    binding.trackButton.visibility = View.VISIBLE
                    binding.statusText.text = getString(R.string.tracking_failed_short)
                    binding.statusOverlay.visibility = View.VISIBLE

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

    // ── Scene update — tracking-state restart logic ───────────────────────────

    private fun onSceneUpdate() {
        meshToggle?.setAvailable(meshRenderer?.hasMesh() == true)
        meshRenderer?.onFrame()

        val frame = arFragment.arSceneView.arFrame ?: return
        val currentState = frame.camera.trackingState

        // Reflect AR tracking availability on the capture button (reference UI behaviour)
        val isTracking = currentState == TrackingState.TRACKING
        binding.trackButton.isClickable = isTracking
        binding.trackButton.alpha = if (isTracking) 1.0f else 0.5f

        // Auto-start tracking only once ARCore reaches TRACKING (not on first configure).
        if (isTracking && pendingAutoStart && !sessionStarted) {
            pendingAutoStart = false
            sessionStarted = true
            Log.d(TAG, "AR tracking established — starting object tracking")
            trackingSession?.start()
        }

        if (currentState != lastTrackingState) {
            val previous = lastTrackingState
            lastTrackingState = currentState

            // Surface the AR tracking state in the status overlay (reference UI behaviour)
            binding.statusText.text = getString(
                when (currentState) {
                    TrackingState.TRACKING -> R.string.tracking_normal
                    TrackingState.PAUSED -> R.string.tracking_paused
                    TrackingState.STOPPED -> R.string.tracking_stopped
                }
            )

            if (previous == TrackingState.TRACKING
                && currentState != TrackingState.TRACKING
                && sessionStarted
                && ObjectTrackingConfig.restartTracking
            ) {
                Log.d(TAG, "AR tracking lost (→ $currentState) — restarting object tracking session")
                trackingSession?.stop()
                trackingSession?.start()
            }
        }
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    /** AR chrome must clear the status bar / gesture handle while the camera stays full-bleed. */
    private fun applyChromeInsets() {
        binding.statusOverlay.marginForSystemBars(top = true)
        binding.topActions.marginForSystemBars(top = true)
        binding.trackButton.marginForSystemBars(bottom = true)
        binding.meshToggle.marginForSystemBars(bottom = true) { watermarkClearance }
        binding.backgroundProgressIndicator.marginForSystemBars(bottom = true)

        MultiSetWatermark.attach(this) { clearance ->
            watermarkClearance = clearance
            ViewCompat.requestApplyInsets(binding.meshToggle)
        }
    }

    private fun setupUI() {
        setupPhoneAnimation()

        meshToggle = MeshToggle(binding.meshToggle) { visible ->
            meshRenderer?.setMeshVisible(visible)
        }

        binding.resetButton.setOnClickListener { resetTracking() }

        binding.trackButton.setOnClickListener {
            trackingSession?.let {
                failureAlert.reset()
                // The session waits captureDelayMs before it captures and reports the request.
                showTrackingOverlay()
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

    private fun showTrackingOverlay() {
        binding.trackingStatusText.text = getString(R.string.tracking_objects)
        binding.trackingOverlay.visibility = View.VISIBLE
        binding.trackButton.visibility = View.GONE
        phoneAnimator?.takeUnless { it.isStarted }?.start()
    }

    private fun setupPhoneAnimation() {
        phoneAnimator = ObjectAnimator.ofFloat(binding.phoneImage, "translationX", 0f, 260f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    /** Clears the outline and re-arms tracking, the way Reset re-arms localization. */
    private fun resetTracking() {
        failureAlert.reset()
        trackingSession?.stop()
        meshRenderer?.clearMeshes()
        phoneAnimator?.cancel()
        binding.trackingOverlay.visibility = View.GONE
        binding.trackButton.visibility = View.VISIBLE
        binding.objectCodeBadge.visibility = View.GONE
        binding.resetButton.visibility = View.GONE
        binding.statusOverlay.visibility = View.GONE

        // Re-arm the same gate the initial start uses: capture before ARCore is TRACKING
        // fails with "Failed to capture frame".
        sessionStarted = false
        pendingAutoStart = ObjectTrackingConfig.autoTracking
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
        trackingSession?.stop()
        meshRenderer?.release()
        trackingSession = null
        meshRenderer = null
        objectAnchorNode = null
    }
}
