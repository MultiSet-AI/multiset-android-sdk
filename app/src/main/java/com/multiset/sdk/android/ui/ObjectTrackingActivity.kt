/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.sdk.android.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.ObjectTrackingResult
import com.multiset.sdk.android.ObjectTrackingConfig
import com.multiset.sdk.android.R
import com.multiset.sdk.android.databinding.ActivityObjectTrackingBinding
import com.multiset.sdk.internal.ar.ObjectTrackingManager
import com.multiset.sdk.internal.camera.ImageProcessor
import com.multiset.sdk.internal.mesh.ObjectMeshHandler
import com.multiset.sdk.internal.network.NetworkManager

class ObjectTrackingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ObjectTrackingSDK"
        const val EXTRA_OBJECT_CODES = "object_codes"
    }

    private lateinit var binding: ActivityObjectTrackingBinding
    private lateinit var arFragment: ArFragment

    private var trackingManager: ObjectTrackingManager? = null
    private var meshHandler: ObjectMeshHandler? = null
    private var isSessionConfigured = false
    private var lastTrackingState = TrackingState.TRACKING

    private var phoneAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = MultiSetSDK.getInternalManager()
        if (manager == null || !manager.isAuthenticated()) {
            Log.e(TAG, "SDK not initialized or not authenticated")
            Toast.makeText(this, "SDK not initialized", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding = ActivityObjectTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get object codes from intent or config
        val objectCodes = intent.getStringArrayExtra(EXTRA_OBJECT_CODES)?.toList()
            ?: ObjectTrackingConfig.objectCodes.toList()

        if (objectCodes.isEmpty()) {
            Log.e(TAG, "No object codes provided")
            Toast.makeText(this, "No object codes configured", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val token = manager.getToken()
        if (token == null) {
            Log.e(TAG, "No auth token available")
            finish()
            return
        }

        // Initialize mesh handler (material loading deferred until AR session is ready)
        meshHandler = ObjectMeshHandler(this, token)

        setupAR(objectCodes, token)
        setupUI()

        Log.d(TAG, "ObjectTrackingActivity started with ${objectCodes.size} object codes: $objectCodes")
    }

    private fun setupTrackingManager(objectCodes: List<String>, token: String) {
        trackingManager = ObjectTrackingManager(
            context = this,
            arFragment = arFragment,
            networkManager = NetworkManager(),
            imageProcessor = ImageProcessor()
        ).apply {
            this.objectCodes = objectCodes
            this.authToken = token
            this.meshHandler = this@ObjectTrackingActivity.meshHandler
            this.autoTracking = ObjectTrackingConfig.autoTracking
            this.backgroundTracking = ObjectTrackingConfig.backgroundTracking
            this.bgTrackingDuration = (ObjectTrackingConfig.bgTrackingDurationSeconds * 1000).toLong()
            this.restartTracking = ObjectTrackingConfig.restartTracking
            this.confidenceCheck = ObjectTrackingConfig.confidenceCheck
            this.confidenceThreshold = ObjectTrackingConfig.confidenceThreshold
            this.showAlert = ObjectTrackingConfig.showAlerts
            this.captureDelay = ObjectTrackingConfig.captureDelayMs
            this.firstTrackingUntilSuccess = ObjectTrackingConfig.firstTrackingUntilSuccess
            this.imageQuality = ObjectTrackingConfig.imageQuality

            this.onTrackingInit = {
                runOnUiThread {
                    showTrackingOverlay()
                    phoneAnimator?.start()
                }
            }

            this.onTrackingRequested = {
                runOnUiThread {
                    binding.trackingStatusText.text = "Tracking objects..."
                }
            }

            this.onTrackingSuccess = { result ->
                runOnUiThread {
                    hideTrackingOverlay()
                    phoneAnimator?.cancel()

                    if (ObjectTrackingConfig.showAlerts) {
                        showToast("Object tracked: ${result.objectCode}")
                    }

                    MultiSetSDK.getCallback()?.onObjectTrackingSuccess(result)
                    Log.d(TAG, "Object tracking success - code: ${result.objectCode}, " +
                            "confidence: ${result.confidence}")
                }
            }

            this.onTrackingFailure = { error ->
                runOnUiThread {
                    hideTrackingOverlay()
                    phoneAnimator?.cancel()

                    if (ObjectTrackingConfig.showAlerts) {
                        showToast("Tracking failed")
                    }

                    MultiSetSDK.getCallback()?.onObjectTrackingFailure(error)
                }
            }
        }
    }

    private fun setupAR(objectCodes: List<String>, token: String) {
        val fragment = supportFragmentManager.findFragmentById(binding.arFragment.id)
        if (fragment !is ArFragment) {
            Log.e(TAG, "ArFragment not found")
            finish()
            return
        }
        arFragment = fragment

        arFragment.viewLifecycleOwnerLiveData.observe(this) { owner ->
            if (owner != null && arFragment.arSceneView != null) {
                arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
                    onSceneUpdate(frameTime)

                    if (!isSessionConfigured) {
                        arFragment.arSceneView.session?.let { session ->
                            isSessionConfigured = true
                            configureSession(session)
                            setupTrackingManager(objectCodes, token)
                            addObjectSpaceToScene()
                            initializeTracking()
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

    private fun addObjectSpaceToScene() {
        val rootNode = trackingManager?.getObjectSpaceRootNode() ?: return
        arFragment.arSceneView.scene.addChild(rootNode)

        // Load outline material now that Filament engine is ready
        meshHandler?.loadOutlineMaterial()
    }

    private fun initializeTracking() {
        if (ObjectTrackingConfig.autoTracking) {
            Log.d(TAG, "Auto-starting object tracking...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val frame = arFragment.arSceneView.arFrame
                if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
                    trackingManager?.startObjectTracking()
                } else {
                    initializeTracking()
                }
            }, 1000)
        }
    }

    private fun setupUI() {
        binding.trackButton.setOnClickListener {
            trackingManager?.startObjectTracking()
        }

        binding.closeButton.setOnClickListener {
            showCloseConfirmationDialog()
        }

        setupPhoneAnimation()
    }

    private fun setupPhoneAnimation() {
        phoneAnimator = ObjectAnimator.ofFloat(binding.phoneImage, "translationX", 0f, 260f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    private fun onSceneUpdate(frameTime: FrameTime) {
        val frame = arFragment.arSceneView.arFrame ?: return
        val currentTrackingState = frame.camera.trackingState

        val isTracking = currentTrackingState == TrackingState.TRACKING
        updateTrackButtonState(isTracking)

        if (currentTrackingState != lastTrackingState) {
            onTrackingStateChanged(currentTrackingState)
            lastTrackingState = currentTrackingState
        }

        // Notify tracking manager for re-tracking on AR state change
        trackingManager?.onFrameUpdate(frame)
    }

    private fun onTrackingStateChanged(newState: TrackingState) {
        runOnUiThread {
            binding.statusText.text = when (newState) {
                TrackingState.TRACKING -> "Tracking Normal"
                TrackingState.PAUSED -> "Tracking Paused"
                TrackingState.STOPPED -> "Tracking Stopped"
            }
        }

        val publicState = when (newState) {
            TrackingState.TRACKING -> com.multiset.sdk.TrackingState.TRACKING
            TrackingState.PAUSED -> com.multiset.sdk.TrackingState.PAUSED
            TrackingState.STOPPED -> com.multiset.sdk.TrackingState.STOPPED
        }
        MultiSetSDK.getCallback()?.onTrackingStateChanged(publicState)
    }

    private fun updateTrackButtonState(enabled: Boolean) {
        binding.trackButton.isClickable = enabled
        binding.trackButton.alpha = if (enabled) 1.0f else 0.5f
    }

    // ==================== Overlay UI ====================

    private fun showTrackingOverlay() {
        binding.trackingOverlay.visibility = View.VISIBLE
        binding.trackButton.visibility = View.GONE
    }

    private fun hideTrackingOverlay() {
        binding.trackingOverlay.visibility = View.GONE
        binding.trackButton.visibility = View.VISIBLE
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
            if (child is android.view.ViewGroup) {
                hideInstructionsRecursively(child)
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showCloseConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Close Object Tracking")
            .setMessage("Would you like to close the Object Tracking scene?")
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ==================== Lifecycle ====================

    override fun onDestroy() {
        super.onDestroy()
        phoneAnimator?.cancel()
        trackingManager?.release()
        trackingManager = null
        meshHandler = null
    }
}
