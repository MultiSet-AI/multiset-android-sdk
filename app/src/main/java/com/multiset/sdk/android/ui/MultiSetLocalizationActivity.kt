/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.sdk.android.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.sdk.GeoCoordinates
import com.multiset.sdk.LocalizationMode
import com.multiset.sdk.LocalizationResult
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.android.LocalizationConfig
import com.multiset.sdk.android.R
import com.multiset.sdk.android.databinding.ActivityLocalizationBinding
import com.multiset.sdk.internal.SDKConfigInternal
import com.multiset.sdk.internal.ar.GizmoNode
import com.multiset.sdk.internal.ar.ImageData
import com.multiset.sdk.internal.ar.ImageMetadata
import com.multiset.sdk.internal.ar.LocalizationConfigInternal
import com.multiset.sdk.internal.camera.ImageProcessor
import com.multiset.sdk.internal.mesh.MapMeshHandler
import com.multiset.sdk.internal.mesh.MeshRenderer
import com.multiset.sdk.internal.mesh.MeshVisualizationOption
import com.multiset.sdk.internal.network.LocalizationSuccessResponse
import com.multiset.sdk.internal.network.NetworkManager
import com.multiset.sdk.internal.network.SingleFrameLocalizationResponse
import com.multiset.sdk.internal.utils.GpsCoordinateHandler
import com.multiset.sdk.internal.utils.GpsCoordinatesInternal
import com.multiset.sdk.internal.utils.Util.Companion.createMatrixFromQuaternion
import com.multiset.sdk.internal.utils.Util.Companion.createTransformMatrix
import com.multiset.sdk.internal.utils.Util.Companion.extractPosition
import com.multiset.sdk.internal.utils.Util.Companion.extractRotation
import com.multiset.sdk.internal.utils.Util.Companion.invertMatrix
import com.multiset.sdk.internal.utils.Util.Companion.multiplyMatrices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Unified AR localization activity supporting both single-frame and multi-frame modes.
 * The localization mode is passed via intent extra [EXTRA_LOCALIZATION_MODE].
 */
class MultiSetLocalizationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MultiSetSDK"
        const val EXTRA_LOCALIZATION_MODE = "localization_mode"
    }

    private lateinit var binding: ActivityLocalizationBinding
    private lateinit var arFragment: ArFragment
    private var gizmoNode: GizmoNode? = null

    // Localization mode
    private var localizationMode: LocalizationMode = LocalizationMode.MULTI_FRAME

    // Configuration
    private var localizationConfig = LocalizationConfigInternal.default()

    // Components
    private val imageProcessor = ImageProcessor()
    private val networkManager = NetworkManager()
    private var meshHandler: MapMeshHandler? = null
    private var meshRenderer: MeshRenderer? = null

    // GPS handling
    private var gpsCoordinateHandler: GpsCoordinateHandler? = null
    private var capturedGpsCoordinates: GpsCoordinatesInternal? = null

    // Single-frame: camera pose captured at time of frame capture
    private var capturedCameraPose: CameraPose? = null

    // Multi-frame: captured frames and upload data
    private val capturedImages = mutableListOf<ImageData>()
    private var uploadData: UploadData? = null

    // State
    private var isLocalizing = false
    private var isFirstLocalization = true
    private var isBackgroundLocalizationRequest = false
    private var lastTrackingState = TrackingState.TRACKING
    private var isSessionConfigured = false

    // Coroutine management
    private var backgroundLocalizationJob: Job? = null
    private var localizationJob: Job? = null

    // Multi-frame animation
    private var phoneAnimator: ObjectAnimator? = null

    // GPS status update handler
    private val gpsStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gpsStatusRunnable = object : Runnable {
        override fun run() {
            updateGpsIndicator()
            gpsStatusHandler.postDelayed(this, 2000)
        }
    }

    // GPS wait handler for auto-localization
    private var gpsWaitAttempts = 0
    private val maxGpsWaitAttempts = 10
    private val gpsWaitRunnable = object : Runnable {
        override fun run() {
            gpsWaitAttempts++
            val hasValidCoordinates = hasValidGpsCoordinates()

            if (hasValidCoordinates) {
                Log.d(TAG, "GPS coordinates available, starting auto-localization")
                startAutoLocalizationNow()
            } else if (gpsWaitAttempts >= maxGpsWaitAttempts) {
                Log.w(TAG, "GPS timeout after ${maxGpsWaitAttempts}s, starting localization without GPS")
                startAutoLocalizationNow()
            } else {
                Log.d(TAG, "Waiting for GPS... attempt $gpsWaitAttempts/$maxGpsWaitAttempts")
                gpsStatusHandler.postDelayed(this, 1000)
            }
        }
    }

    // Location permission launcher
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            Log.d(TAG, "Location permission granted")
            initializeGps()
            startGpsStatusUpdates()
            waitForGpsAndStartLocalization()
        } else {
            Log.w(TAG, "Location permission denied - geoHint will not be available")
            updateGpsIndicator(false)
            if (localizationConfig.autoLocalize) {
                startAutoLocalizationDelayed(1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = MultiSetSDK.getInternalManager()
        if (manager == null || !manager.isAuthenticated()) {
            Log.e(TAG, "SDK not initialized or not authenticated")
            Toast.makeText(this, "SDK not initialized", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Read localization mode from intent
        val modeName = intent.getStringExtra(EXTRA_LOCALIZATION_MODE)
        localizationMode = try {
            LocalizationMode.valueOf(modeName ?: LocalizationMode.MULTI_FRAME.name)
        } catch (e: IllegalArgumentException) {
            LocalizationMode.MULTI_FRAME
        }

        binding = ActivityLocalizationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load config from app's LocalizationConfig
        LocalizationConfig.validate()
        Log.d(TAG, "LocalizationConfig.autoLocalize = ${LocalizationConfig.autoLocalize}")
        Log.d(TAG, "LocalizationConfig.enableGeoHint = ${LocalizationConfig.enableGeoHint}")

        // If geoHint and geoCoordinatesInResponse are both enabled, disable silent retry
        val useFirstLocalizationUntilSuccess = if (LocalizationConfig.enableGeoHint && LocalizationConfig.includeGeoCoordinatesInResponse) {
            false
        } else {
            LocalizationConfig.firstLocalizationUntilSuccess
        }

        localizationConfig = LocalizationConfigInternal(
            autoLocalize = LocalizationConfig.autoLocalize,
            backgroundLocalization = LocalizationConfig.backgroundLocalization,
            bgLocalizationDurationSeconds = LocalizationConfig.backgroundLocalizationIntervalSeconds,
            relocalization = LocalizationConfig.relocalization,
            numberOfFrames = LocalizationConfig.numberOfFrames,
            frameCaptureIntervalMs = LocalizationConfig.frameCaptureIntervalMs,
            confidenceCheck = LocalizationConfig.confidenceCheck,
            confidenceThreshold = LocalizationConfig.confidenceThreshold,
            firstLocalizationUntilSuccess = useFirstLocalizationUntilSuccess,
            showAlerts = LocalizationConfig.showAlerts,
            meshVisualization = LocalizationConfig.enableMeshVisualization,
            passGeoPose = LocalizationConfig.enableGeoHint,
            geoCoordinatesInResponse = LocalizationConfig.includeGeoCoordinatesInResponse,
            imageQuality = LocalizationConfig.imageQuality
        )
        Log.d(TAG, "localizationConfig.autoLocalize = ${localizationConfig.autoLocalize}")

        setupAR()
        setupUI()

        val modeLabel = if (localizationMode == LocalizationMode.SINGLE_FRAME) "single-frame" else "multi-frame"
        Log.d(TAG, "MultiSetLocalizationActivity started in $modeLabel mode with config: $localizationConfig")
    }

    private fun setupAR() {
        val fragment = supportFragmentManager.findFragmentById(binding.arFragment.id)
        if (fragment is ArFragment) {
            arFragment = fragment
            arFragment.viewLifecycleOwnerLiveData.observe(this) { owner ->
                if (owner != null && arFragment.arSceneView != null) {
                    arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
                        onSceneUpdate(frameTime)

                        if (!isSessionConfigured) {
                            arFragment.arSceneView.session?.let { session ->
                                isSessionConfigured = true
                                configureSession(session)
                                addGizmoToScene()
                                initializeMeshComponents()
                                initializeLocalization()
                            }
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

    private fun initializeMeshComponents() {
        val token = MultiSetSDK.getInternalManager()?.getToken()
        if (token != null) {
            meshHandler = MapMeshHandler(this, token).apply {
                visualizationOption = if (localizationConfig.meshVisualization)
                    MeshVisualizationOption.ENABLE_VISUALIZATION
                else
                    MeshVisualizationOption.NO_MESH
            }
            meshRenderer = MeshRenderer(this).apply {
                setCameraPositionProvider { getCurrentCameraWorldPosition() }
            }
            Log.d(TAG, "Mesh components initialized")
        }
    }

    private fun getCurrentCameraWorldPosition(): Vector3? {
        return try {
            val frame = arFragment.arSceneView.arFrame ?: return null
            if (frame.camera.trackingState != TrackingState.TRACKING) return null
            val pose = frame.camera.pose
            Vector3(pose.tx(), pose.ty(), pose.tz())
        } catch (e: Exception) {
            null
        }
    }

    private fun initializeLocalization() {
        Log.d(TAG, "initializeLocalization: passGeoPose=${localizationConfig.passGeoPose}, autoLocalize=${localizationConfig.autoLocalize}")
        if (localizationConfig.passGeoPose) {
            checkAndRequestLocationPermission()
        } else {
            if (localizationConfig.autoLocalize) {
                Log.d(TAG, "Starting auto-localization...")
                startAutoLocalizationDelayed(1000L)
            } else {
                Log.d(TAG, "Auto-localization disabled, waiting for manual trigger")
            }
        }
    }

    private fun setupUI() {
        binding.localizeButton.visibility = View.VISIBLE

        binding.localizeButton.setOnClickListener {
            localizeFrame()
        }

        binding.resetButton.setOnClickListener {
            resetWorldOrigin()
        }

        binding.closeButton.setOnClickListener {
            showCloseConfirmationDialog()
        }

        setupPhoneAnimation()
    }

    private fun setupPhoneAnimation() {
        phoneAnimator = ObjectAnimator.ofFloat(binding.phoneImage, "translationX", 0f, 130f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    private fun onSceneUpdate(frameTime: FrameTime) {
        val frame = arFragment.arSceneView.arFrame ?: return
        val currentTrackingState = frame.camera.trackingState

        val isTracking = currentTrackingState == TrackingState.TRACKING
        updateLocalizeButtonState(isTracking && !isLocalizing)

        if (currentTrackingState != lastTrackingState) {
            onTrackingStateChanged(currentTrackingState)
            lastTrackingState = currentTrackingState
        }
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

        if (localizationConfig.relocalization && newState != lastTrackingState) {
            handleTrackingStateChange(newState)
        }
    }

    private fun handleTrackingStateChange(newState: TrackingState) {
        when (newState) {
            TrackingState.TRACKING -> { /* Tracking recovered */ }
            TrackingState.PAUSED, TrackingState.STOPPED -> {
                if (!isLocalizing && !isFirstLocalization) {
                    Log.d(TAG, "AR Tracking Lost - Triggering relocalization")
                    lifecycleScope.launch {
                        delay(1000)
                        if (!isLocalizing && !isFirstLocalization) {
                            localizeFrame()
                        }
                    }
                }
            }
        }
    }

    private fun updateLocalizeButtonState(enabled: Boolean) {
        binding.localizeButton.isClickable = enabled
        binding.localizeButton.alpha = if (enabled) 1.0f else 0.5f
    }

    // ==================== Localization ====================

    private fun localizeFrame() {
        if (isLocalizing) {
            Log.w(TAG, "Localization already in progress")
            return
        }

        val frame = arFragment.arSceneView.arFrame
        if (frame == null || frame.camera.trackingState != TrackingState.TRACKING) {
            if (localizationConfig.showAlerts) {
                showToast("Please wait for tracking to stabilize")
            }
            return
        }

        isBackgroundLocalizationRequest = false

        when (localizationMode) {
            LocalizationMode.SINGLE_FRAME -> startSingleFrameLocalization(frame)
            LocalizationMode.MULTI_FRAME -> startMultiFrameLocalization()
        }
    }

    private fun startAutoLocalizationDelayed(delayMs: Long) {
        lifecycleScope.launch {
            delay(delayMs)
            if (!isLocalizing) {
                val frame = arFragment.arSceneView.arFrame
                if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
                    localizeFrame()
                } else {
                    startAutoLocalizationDelayed(500L)
                }
            }
        }
    }

    private fun startAutoLocalizationNow() {
        gpsStatusHandler.removeCallbacks(gpsWaitRunnable)
        startAutoLocalizationDelayed(500L)
    }

    // ==================== Single-Frame Localization ====================

    private fun startSingleFrameLocalization(frame: Frame) {
        isLocalizing = true

        Log.d(TAG, "Starting single frame localization (background: $isBackgroundLocalizationRequest)")

        capturedCameraPose = getCurrentCameraPose(frame)

        if (localizationConfig.passGeoPose && gpsCoordinateHandler != null) {
            capturedGpsCoordinates = gpsCoordinateHandler?.getCurrentCoordinates()
            if (capturedGpsCoordinates?.isValid() == true) {
                Log.d(TAG, "GPS coordinates captured: ${capturedGpsCoordinates?.toGeoHintString()}")
            } else {
                capturedGpsCoordinates = null
            }
        } else {
            capturedGpsCoordinates = null
        }

        if (isBackgroundLocalizationRequest) {
            showBackgroundProgress()
        } else {
            showFrameCaptureOverlay()
            phoneAnimator?.start()
        }

        lifecycleScope.launch {
            try {
                val imageData = captureSingleFrame(frame)
                if (imageData != null) {
                    sendSingleFrameRequest(imageData)
                } else {
                    handleLocalizationFailure("Failed to capture frame")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during capture: ${e.message}", e)
                handleLocalizationFailure("Capture failed: ${e.message}")
            }
        }
    }

    private fun getCurrentCameraPose(frame: Frame): CameraPose {
        val pose = frame.camera.pose
        val position = Vector3(pose.tx(), pose.ty(), pose.tz())
        var rotation = Quaternion(pose.qx(), pose.qy(), pose.qz(), pose.qw())

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            val orientationCorrection = Quaternion.axisAngle(Vector3(0f, 0f, 1f), 90f)
            rotation = Quaternion.multiply(rotation, orientationCorrection)
        }

        return CameraPose(position, rotation)
    }

    private suspend fun captureSingleFrame(frame: Frame): CapturedImageData? {
        // Acquire camera image on the main thread immediately —
        // ARCore frames become stale within one frame interval (~33ms)
        val image: Image
        try {
            image = frame.acquireCameraImage() ?: return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire camera image: ${e.message}", e)
            return null
        }

        return withContext(Dispatchers.Default) {
            try {
                val originalWidth = image.width
                val originalHeight = image.height

                val rawBitmap = imageProcessor.imageToRgbBitmap(image)
                image.close()

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val finalBitmap: Bitmap = if (!isLandscape) {
                    rotateBitmap(rawBitmap, 90f)
                } else {
                    rawBitmap
                }

                val processedData = imageProcessor.processImageForLocalization(
                    finalBitmap,
                    frame,
                    this@MultiSetLocalizationActivity,
                    originalWidth,
                    originalHeight
                )

                val outputStream = ByteArrayOutputStream()
                val compressResult = processedData.bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    localizationConfig.imageQuality,
                    outputStream
                )

                if (!compressResult) return@withContext null

                CapturedImageData(
                    imageBytes = outputStream.toByteArray(),
                    width = processedData.width,
                    height = processedData.height,
                    fx = processedData.fx,
                    fy = processedData.fy,
                    px = processedData.px,
                    py = processedData.py
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in captureSingleFrame: ${e.message}", e)
                null
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun sendSingleFrameRequest(imageData: CapturedImageData) {
        val token = MultiSetSDK.getInternalManager()?.getToken()
        if (token == null) {
            handleLocalizationFailure("No auth token")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val parameters = buildCommonParameters(
                    fx = imageData.fx, fy = imageData.fy,
                    px = imageData.px, py = imageData.py,
                    width = imageData.width, height = imageData.height
                )

                Log.d(TAG, "Sending single frame localization request")

                val response = networkManager.sendSingleFrameLocalizationRequest(
                    token,
                    parameters,
                    imageData.imageBytes
                )

                withContext(Dispatchers.Main) {
                    handleSingleFrameResponse(response)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Log.e(TAG, "Localization request failed: ${e.message}", e)
                handleLocalizationFailure("API error: ${e.message}")
            }
        }
    }

    private fun handleSingleFrameResponse(response: SingleFrameLocalizationResponse) {
        isLocalizing = false

        val responsePosition = response.position
        val responseRotation = response.rotation

        if (response.poseFound && responsePosition != null && responseRotation != null) {
            if (localizationConfig.confidenceCheck) {
                val confidence = response.confidence ?: 1.0f
                if (confidence < localizationConfig.confidenceThreshold) {
                    Log.w(TAG, "Confidence $confidence below threshold")
                    handleLocalizationFailure("Low confidence: $confidence")
                    return
                }
            }

            val estimatedPosition = Vector3(responsePosition.x, responsePosition.y, responsePosition.z)
            val estimatedRotation = Quaternion(responseRotation.x, responseRotation.y, responseRotation.z, responseRotation.w)

            val resultPose: ResultPose
            val cameraPose = capturedCameraPose

            if (cameraPose != null) {
                resultPose = poseHandler(estimatedPosition, estimatedRotation, cameraPose.position, cameraPose.rotation)
            } else {
                resultPose = ResultPose(estimatedPosition, estimatedRotation)
            }

            if (isFirstLocalization) {
                isFirstLocalization = false
            }

            updateGizmoPosition(resultPose.position, resultPose.rotation)

            if (localizationConfig.meshVisualization) {
                loadMeshAfterLocalization(response.mapCodes)
            }

            if (localizationConfig.showAlerts && !isBackgroundLocalizationRequest) {
                showToast(getString(R.string.localization_success))
            }

            val mapCode = response.mapCodes.firstOrNull() ?: ""
            val publicResult = LocalizationResult(
                mapCode = mapCode,
                mapCodes = response.mapCodes,
                position = floatArrayOf(resultPose.position.x, resultPose.position.y, resultPose.position.z),
                rotation = floatArrayOf(resultPose.rotation.x, resultPose.rotation.y, resultPose.rotation.z, resultPose.rotation.w),
                confidence = response.confidence,
                geoCoordinates = null
            )
            MultiSetSDK.getCallback()?.onLocalizationSuccess(publicResult)

            // Keep the localization animation visible for 1 second before hiding
            phoneAnimator?.cancel()
            lifecycleScope.launch {
                delay(1000)
                hideAllOverlays()
            }

            Log.d(TAG, "Single frame localization successful!")
        } else {
            handleLocalizationFailure("Pose not found")
        }

        if (localizationConfig.backgroundLocalization) {
            scheduleBackgroundLocalization()
        }
    }

    // ==================== Multi-Frame Localization ====================

    private fun startMultiFrameLocalization() {
        capturedImages.clear()
        uploadData = null
        isLocalizing = true

        if (localizationConfig.passGeoPose && gpsCoordinateHandler != null) {
            capturedGpsCoordinates = gpsCoordinateHandler?.getCurrentCoordinates()
        } else {
            capturedGpsCoordinates = null
        }

        if (isBackgroundLocalizationRequest) {
            showBackgroundProgress()
        } else {
            showFrameCaptureOverlay()
            phoneAnimator?.start()
        }

        localizationJob = lifecycleScope.launch {
            captureFrames()
        }
    }

    private suspend fun captureFrames() {
        val numberOfFrames = localizationConfig.numberOfFrames
        val captureInterval = localizationConfig.frameCaptureIntervalMs

        for (i in 0 until numberOfFrames) {
            if (!isLocalizing) break

            val frame = arFragment.arSceneView.arFrame
            if (frame == null || frame.camera.trackingState != TrackingState.TRACKING) {
                delay(captureInterval)
                continue
            }

            val imageData = captureMultiFrame(frame)
            if (imageData != null) {
                capturedImages.add(imageData)
                Log.d(TAG, "Frame ${capturedImages.size}/$numberOfFrames captured")

                runOnUiThread {
                    binding.multiFrameStatusText.text = "Capturing frames (${capturedImages.size}/$numberOfFrames)..."
                }
            }

            if (capturedImages.size < numberOfFrames) {
                delay(captureInterval)
            }
        }

        if (capturedImages.size >= numberOfFrames) {
            runOnUiThread {
                phoneAnimator?.cancel()
                showApiLoadingOverlay()
            }
            sendMultiFrameRequest()
        } else {
            handleLocalizationFailure("Failed to capture enough frames")
        }
    }

    private suspend fun captureMultiFrame(frame: Frame): ImageData? {
        // Acquire camera image and pose on the main thread immediately —
        // ARCore frames become stale within one frame interval (~33ms)
        val pose = frame.camera.pose
        val position = Vector3(pose.tx(), pose.ty(), pose.tz())
        var rotation = Quaternion(pose.qx(), pose.qy(), pose.qz(), pose.qw())

        val image: Image
        try {
            image = frame.acquireCameraImage() ?: return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire camera image: ${e.message}", e)
            return null
        }

        return withContext(Dispatchers.Default) {
            try {
                val originalWidth = image.width
                val originalHeight = image.height

                val rawBitmap = imageProcessor.imageToRgbBitmap(image)
                image.close()

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val finalBitmap: Bitmap
                if (!isLandscape) {
                    finalBitmap = rotateBitmap(rawBitmap, 90f)
                    val orientationCorrection = Quaternion.axisAngle(Vector3(0f, 0f, 1f), 90f)
                    rotation = Quaternion.multiply(rotation, orientationCorrection)
                } else {
                    finalBitmap = rawBitmap
                }

                val processedData = imageProcessor.processImageForLocalization(
                    finalBitmap,
                    frame,
                    this@MultiSetLocalizationActivity,
                    originalWidth,
                    originalHeight
                )

                if (uploadData == null) {
                    uploadData = UploadData(
                        width = processedData.width,
                        height = processedData.height,
                        px = processedData.px,
                        py = processedData.py,
                        fx = processedData.fx,
                        fy = processedData.fy
                    )
                }

                val outputStream = ByteArrayOutputStream()
                processedData.bitmap.compress(Bitmap.CompressFormat.JPEG, localizationConfig.imageQuality, outputStream)

                ImageData(
                    imageBytes = outputStream.toByteArray(),
                    metadata = ImageMetadata(
                        x = position.x,
                        y = position.y,
                        z = position.z,
                        qx = rotation.x,
                        qy = rotation.y,
                        qz = rotation.z,
                        qw = rotation.w
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in captureMultiFrame: ${e.message}", e)
                null
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun sendMultiFrameRequest() {
        val token = MultiSetSDK.getInternalManager()?.getToken()
        if (token == null || uploadData == null || capturedImages.isEmpty()) {
            handleLocalizationFailure("Missing data")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val parameters = buildCommonParameters(
                    fx = uploadData!!.fx, fy = uploadData!!.fy,
                    px = uploadData!!.px, py = uploadData!!.py,
                    width = uploadData!!.width, height = uploadData!!.height
                )

                val response = networkManager.sendMultiFrameLocalizationRequest(
                    token,
                    parameters,
                    capturedImages
                )

                withContext(Dispatchers.Main) {
                    handleMultiFrameResponse(response)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Log.e(TAG, "Localization request failed: ${e.message}", e)
                handleLocalizationFailure("API error: ${e.message}")
            }
        }
    }

    private fun handleMultiFrameResponse(response: LocalizationSuccessResponse) {
        isLocalizing = false

        if (response.poseFound) {
            if (localizationConfig.confidenceCheck) {
                val confidence = response.confidence ?: 1.0f
                if (confidence < localizationConfig.confidenceThreshold) {
                    handleLocalizationFailure("Low confidence: $confidence")
                    return
                }
            }

            val resultPose = poseHandlerMultiFrame(response)

            if (isFirstLocalization) {
                isFirstLocalization = false
            }

            hideAllOverlays()
            updateGizmoPosition(resultPose.position, resultPose.rotation)

            if (localizationConfig.meshVisualization) {
                loadMeshAfterLocalization(response.mapCodes)
            }

            if (localizationConfig.showAlerts && !isBackgroundLocalizationRequest) {
                showToast(getString(R.string.localization_success))
            }

            val mapCode = response.mapCodes.firstOrNull() ?: ""
            val publicResult = LocalizationResult(
                mapCode = mapCode,
                mapCodes = response.mapCodes,
                position = floatArrayOf(resultPose.position.x, resultPose.position.y, resultPose.position.z),
                rotation = floatArrayOf(resultPose.rotation.x, resultPose.rotation.y, resultPose.rotation.z, resultPose.rotation.w),
                confidence = response.confidence,
                geoCoordinates = null
            )
            MultiSetSDK.getCallback()?.onLocalizationSuccess(publicResult)

            Log.d(TAG, "Multi-frame localization successful!")
        } else {
            handleLocalizationFailure("Pose not found")
        }

        if (localizationConfig.backgroundLocalization) {
            scheduleBackgroundLocalization()
        }
    }

    // ==================== Shared Request Helpers ====================

    private fun buildCommonParameters(
        fx: Float, fy: Float,
        px: Float, py: Float,
        width: Int, height: Int
    ): MutableMap<String, String> {
        val parameters = mutableMapOf(
            "isRightHanded" to "true",
            "fx" to fx.toString(),
            "fy" to fy.toString(),
            "px" to px.toString(),
            "py" to py.toString(),
            "width" to width.toString(),
            "height" to height.toString()
        )

        when (SDKConfigInternal.getActiveMapType()) {
            SDKConfigInternal.MapType.MAP -> {
                if (SDKConfigInternal.MAP_CODE.isNotEmpty()) {
                    parameters["mapCode"] = SDKConfigInternal.MAP_CODE
                }
            }
            SDKConfigInternal.MapType.MAP_SET -> {
                if (SDKConfigInternal.MAP_SET_CODE.isNotEmpty()) {
                    parameters["mapSetCode"] = SDKConfigInternal.MAP_SET_CODE
                }
            }
        }

        if (localizationConfig.passGeoPose && capturedGpsCoordinates?.isValid() == true) {
            parameters["geoHint"] = capturedGpsCoordinates!!.toGeoHintString()
        }

        if (localizationConfig.geoCoordinatesInResponse) {
            parameters["convertToGeoCoordinates"] = "true"
        }

        return parameters
    }

    // ==================== Failure Handling ====================

    private fun handleLocalizationFailure(error: String?) {
        isLocalizing = false
        phoneAnimator?.cancel()

        if (shouldSilentlyRetry()) {
            Log.d(TAG, "First localization failed, retrying silently...")
            if (localizationConfig.backgroundLocalization) {
                scheduleBackgroundLocalization()
            } else {
                lifecycleScope.launch {
                    delay(1000)
                    if (!isLocalizing) {
                        val frame = arFragment.arSceneView.arFrame
                        if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
                            isBackgroundLocalizationRequest = false
                            when (localizationMode) {
                                LocalizationMode.SINGLE_FRAME -> startSingleFrameLocalization(frame)
                                LocalizationMode.MULTI_FRAME -> startMultiFrameLocalization()
                            }
                        }
                    }
                }
            }
            return
        }

        hideAllOverlays()

        Log.e(TAG, "Localization failed: $error")

        if (localizationConfig.showAlerts && !isBackgroundLocalizationRequest) {
            showToast(getString(R.string.localization_failed))
        }

        MultiSetSDK.getCallback()?.onLocalizationFailure(error ?: "Unknown error")

        if (localizationConfig.backgroundLocalization) {
            scheduleBackgroundLocalization()
        }
    }

    private fun shouldSilentlyRetry(): Boolean =
        localizationConfig.firstLocalizationUntilSuccess && isFirstLocalization && !isBackgroundLocalizationRequest

    private fun scheduleBackgroundLocalization() {
        backgroundLocalizationJob?.cancel()

        backgroundLocalizationJob = lifecycleScope.launch {
            val delayMs = (localizationConfig.bgLocalizationDurationSeconds * 1000).toLong()
            Log.d(TAG, "Scheduling background localization in ${localizationConfig.bgLocalizationDurationSeconds}s")
            delay(delayMs)

            if (!isLocalizing) {
                val frame = arFragment.arSceneView.arFrame
                if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
                    Log.d(TAG, "Starting background localization...")
                    isBackgroundLocalizationRequest = true
                    when (localizationMode) {
                        LocalizationMode.SINGLE_FRAME -> startSingleFrameLocalization(frame)
                        LocalizationMode.MULTI_FRAME -> startMultiFrameLocalization()
                    }
                }
            }
        }
    }

    // ==================== Pose Calculation ====================

    private fun poseHandler(
        estPosition: Vector3,
        estRotation: Quaternion,
        trackerPosition: Vector3,
        trackerRotation: Quaternion
    ): ResultPose {
        val estRotMatrix = createMatrixFromQuaternion(estRotation)
        val estMatrix = createTransformMatrix(estRotMatrix, estPosition)
        val invEstMatrix = invertMatrix(estMatrix)

        val trackerMatrix = createTransformMatrix(
            createMatrixFromQuaternion(trackerRotation),
            trackerPosition
        )

        val resultMatrix = multiplyMatrices(trackerMatrix, invEstMatrix)

        val resultPosition = extractPosition(resultMatrix)
        val resultRotation = extractRotation(resultMatrix)

        return ResultPose(resultPosition, resultRotation)
    }

    private fun poseHandlerMultiFrame(response: LocalizationSuccessResponse): ResultPose {
        val estRotation = Quaternion(
            response.estimatedPose.rotation.x,
            response.estimatedPose.rotation.y,
            response.estimatedPose.rotation.z,
            response.estimatedPose.rotation.w
        )
        val estPosition = Vector3(
            response.estimatedPose.position.x,
            response.estimatedPose.position.y,
            response.estimatedPose.position.z
        )

        val estRotMatrix = createMatrixFromQuaternion(estRotation)
        val estMatrix = createTransformMatrix(estRotMatrix, estPosition)
        val invEstMatrix = invertMatrix(estMatrix)

        val trackerRotation = Quaternion(
            response.trackingPose.rotation.x,
            response.trackingPose.rotation.y,
            response.trackingPose.rotation.z,
            response.trackingPose.rotation.w
        )
        val trackerPosition = Vector3(
            response.trackingPose.position.x,
            response.trackingPose.position.y,
            response.trackingPose.position.z
        )

        val trackerMatrix = createTransformMatrix(
            createMatrixFromQuaternion(trackerRotation),
            trackerPosition
        )

        val resultMatrix = multiplyMatrices(trackerMatrix, invEstMatrix)

        val resultPosition = extractPosition(resultMatrix)
        val resultRotation = extractRotation(resultMatrix)

        return ResultPose(resultPosition, resultRotation)
    }

    // ==================== Mesh Loading ====================

    private fun loadMeshAfterLocalization(mapCodes: List<String>) {
        if (mapCodes.isEmpty()) return

        val localizedMapCode = mapCodes.first()
        Log.d(TAG, "Loading mesh for map: $localizedMapCode")

        lifecycleScope.launch {
            try {
                when (SDKConfigInternal.getActiveMapType()) {
                    SDKConfigInternal.MapType.MAP -> {
                        meshHandler?.loadMeshForMap(localizedMapCode) { meshResult ->
                            renderMesh(meshResult)
                        }
                    }
                    SDKConfigInternal.MapType.MAP_SET -> {
                        meshHandler?.loadMeshForMapSet(
                            SDKConfigInternal.MAP_SET_CODE,
                            localizedMapCode
                        ) { meshResult ->
                            renderMesh(meshResult)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load mesh: ${e.message}", e)
            }
        }
    }

    private fun renderMesh(meshResult: com.multiset.sdk.internal.mesh.MeshResult) {
        val parentNode = gizmoNode ?: return
        val cameraPos = getCurrentCameraWorldPosition()

        meshRenderer?.renderMesh(meshResult, parentNode, cameraPos) { success ->
            if (success) {
                Log.d(TAG, "Mesh rendered successfully for map: ${meshResult.mapId}")
                MultiSetSDK.getCallback()?.onMeshLoaded(meshResult.mapId)
            } else {
                Log.e(TAG, "Failed to render mesh for map: ${meshResult.mapId}")
                MultiSetSDK.getCallback()?.onMeshLoadError("Failed to render mesh")
            }
        }
    }

    // ==================== Gizmo ====================

    private fun addGizmoToScene() {
        val scene = arFragment.arSceneView.scene
        gizmoNode = GizmoNode(this)
        scene.addChild(gizmoNode)
        gizmoNode?.show()
    }

    private fun updateGizmoPosition(position: Vector3, rotation: Quaternion) {
        gizmoNode?.let { node ->
            node.localPosition = position
            node.localRotation = rotation
            node.show()
        }
    }

    // ==================== Reset ====================

    private fun resetWorldOrigin() {
        val session = arFragment.arSceneView.session ?: return
        session.pause()

        val config = Config(session)
        session.configure(config)
        session.resume()

        localizationJob?.cancel()
        backgroundLocalizationJob?.cancel()
        isLocalizing = false
        isFirstLocalization = true
        capturedImages.clear()
        uploadData = null
        meshRenderer?.removeMesh()

        gizmoNode?.let {
            it.localPosition = Vector3.zero()
            it.localRotation = Quaternion.identity()
        }

        showToast("World origin reset")

        if (localizationConfig.autoLocalize) {
            startAutoLocalizationDelayed(1000L)
        }
    }

    // ==================== GPS Handling ====================

    private fun checkAndRequestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeGps()
                startGpsStatusUpdates()
                waitForGpsAndStartLocalization()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                showGpsIndicator()
                showLocationPermissionRationale()
            }
            else -> {
                showGpsIndicator()
                requestLocationPermission()
            }
        }
    }

    private fun showLocationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage("Location permission is needed to improve localization accuracy.")
            .setPositiveButton("Grant") { _, _ ->
                requestLocationPermission()
            }
            .setNegativeButton("Skip") { dialog, _ ->
                dialog.dismiss()
                if (localizationConfig.autoLocalize) {
                    startAutoLocalizationDelayed(1000L)
                }
            }
            .show()
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun initializeGps() {
        gpsCoordinateHandler = GpsCoordinateHandler(this)
        gpsCoordinateHandler?.enableGpsHandler()
    }

    private fun hasValidGpsCoordinates(): Boolean =
        gpsCoordinateHandler?.gpsCoordinates?.isValid() == true

    private fun waitForGpsAndStartLocalization() {
        if (!localizationConfig.autoLocalize) return

        gpsWaitAttempts = 0
        if (hasValidGpsCoordinates()) {
            startAutoLocalizationNow()
        } else {
            gpsStatusHandler.postDelayed(gpsWaitRunnable, 1000)
        }
    }

    private fun showGpsIndicator() {
        if (localizationConfig.passGeoPose) {
            binding.gpsIndicator.visibility = View.VISIBLE
            updateGpsIndicator(false)
        }
    }

    private fun startGpsStatusUpdates() {
        showGpsIndicator()
        gpsStatusHandler.post(gpsStatusRunnable)
    }

    private fun stopGpsStatusUpdates() {
        gpsStatusHandler.removeCallbacks(gpsStatusRunnable)
        gpsStatusHandler.removeCallbacks(gpsWaitRunnable)
    }

    private fun updateGpsIndicator() {
        updateGpsIndicator(hasValidGpsCoordinates())
    }

    private fun updateGpsIndicator(isActive: Boolean) {
        runOnUiThread {
            if (localizationConfig.passGeoPose) {
                binding.gpsIndicator.visibility = View.VISIBLE
                if (isActive) {
                    binding.gpsStatusDot.setBackgroundResource(R.drawable.gps_dot_active)
                } else {
                    binding.gpsStatusDot.setBackgroundResource(R.drawable.gps_dot_inactive)
                }
            } else {
                binding.gpsIndicator.visibility = View.GONE
            }
        }
    }

    // ==================== Overlay UI ====================

    private fun showFrameCaptureOverlay() {
        binding.backgroundProgressIndicator.visibility = View.GONE
        binding.localizeButton.visibility = View.GONE
        binding.apiLoadingOverlay.visibility = View.GONE
        binding.singleFrameOverlay.visibility = View.GONE
        binding.multiFrameOverlay.visibility = View.VISIBLE
    }

    private fun showApiLoadingOverlay() {
        binding.backgroundProgressIndicator.visibility = View.GONE
        binding.localizeButton.visibility = View.GONE
        binding.singleFrameOverlay.visibility = View.GONE
        binding.multiFrameOverlay.visibility = View.GONE
        binding.apiLoadingOverlay.visibility = View.VISIBLE
    }

    private fun showBackgroundProgress() {
        binding.backgroundProgressIndicator.visibility = View.VISIBLE
    }

    private fun hideAllOverlays() {
        binding.singleFrameOverlay.visibility = View.GONE
        binding.multiFrameOverlay.visibility = View.GONE
        binding.apiLoadingOverlay.visibility = View.GONE
        binding.backgroundProgressIndicator.visibility = View.GONE
        binding.localizeButton.visibility = View.VISIBLE
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
            .setTitle("Close Localization")
            .setMessage("Would you like to close the Localization scene?")
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ==================== Utility ====================

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ==================== Data Classes ====================

    private data class CameraPose(
        val position: Vector3,
        val rotation: Quaternion
    )

    private data class CapturedImageData(
        val imageBytes: ByteArray,
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val px: Float,
        val py: Float
    )

    private data class UploadData(
        val width: Int,
        val height: Int,
        val px: Float,
        val py: Float,
        val fx: Float,
        val fy: Float
    )

    private data class ResultPose(
        val position: Vector3,
        val rotation: Quaternion
    )

    // ==================== Lifecycle ====================

    override fun onDestroy() {
        super.onDestroy()
        stopGpsStatusUpdates()
        localizationJob?.cancel()
        backgroundLocalizationJob?.cancel()
        phoneAnimator?.cancel()
        gpsCoordinateHandler?.stopGps()
        gpsCoordinateHandler = null
        meshRenderer?.removeMesh()
        meshRenderer = null
        meshHandler = null
    }
}
