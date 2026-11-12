/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.geopose

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.camera.ImageProcessor
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.model.LocalizationStage
import com.multiset.sdk.model.RecoveryPhase
import com.multiset.sdk.session.LocalizationSession
import com.multiset.sdk.source.GeoHintProvider
import com.multiset.sdk.ui.MultiSetWatermark
import com.multiset.xr.R
import com.multiset.xr.ar.ArFrameSource
import com.multiset.xr.ar.reportArTracking
import com.multiset.xr.config.LocalizationConfig
import com.multiset.xr.databinding.ActivityGeoposeMapBinding
import com.multiset.xr.geopose.GeoPoseMapModel
import com.multiset.xr.geopose.GeoPoseMapRenderer
import com.multiset.xr.geopose.GeoPoseMapState
import com.multiset.xr.geopose.GeoPoseMath
import com.multiset.xr.geopose.GeoPoseStatus
import com.multiset.xr.geopose.GpsTracker
import com.multiset.xr.geopose.MapStyle
import com.multiset.xr.ui.FailureAlert
import com.multiset.xr.ui.MultiSetLocalizationActivity
import com.multiset.xr.ui.marginForSystemBars
import com.multiset.xr.ui.runstatus.RunStatusCopy
import com.multiset.xr.ui.runstatus.RunStatusIndicator
import com.multiset.xr.ui.runstatus.RunStatusState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import java.io.File
import java.util.Locale
import com.multiset.sdk.model.TrackingState as SdkTrackingState

/**
 * Geo Pose Map View: the AR localization loop over the camera, and under it a map that plots each
 * fix's GeoPose (position and compass heading) beside the device's own GPS track. For a
 * georeferenced map or map set; it asks for the GeoPose whatever the Settings say.
 */
class GeoPoseMapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GeoPoseMapActivity"
        private const val GPS_REFRESH_MS = 1_000L

        // A geo hint narrows the search to where the fix says; one older than this is not sent.
        private const val GEO_HINT_MAX_AGE_MS = 120_000L
        private const val DEFAULT_ZOOM = 19.0
    }

    private lateinit var binding: ActivityGeoposeMapBinding
    private lateinit var arFragment: ArFragment
    private lateinit var renderer: GeoPoseMapRenderer
    private lateinit var gpsTracker: GpsTracker
    private lateinit var sheet: BottomSheetBehavior<View>

    private val model = GeoPoseMapModel()
    private val failureAlert = FailureAlert(R.string.error_title_localization)
    private var localizationSession: LocalizationSession? = null
    private var frameSource: ArFrameSource? = null
    private var runStatus: RunStatusIndicator? = null
    private var geoHintProvider: GeoHintProvider? = null

    private var localizationMode: LocalizationMode = MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE
    private var isSessionConfigured = false
    private var sessionStarted = false
    private var pendingAutoStart = false
    // Null until the first frame, so the session hears the first state too
    private var lastTrackingState: TrackingState? = null
    private var lastFailureReason: TrackingFailureReason? = null
    private var watermarkClearance = 0
    private var isFullscreen = false
    private var mapCentered = false

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            gpsTracker.start()
            renderNotice(model.state.value)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // osmdroid reads these when the MapView is inflated; the cache stays in app storage.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        binding = ActivityGeoposeMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localizationMode = try {
            LocalizationMode.valueOf(
                intent.getStringExtra(MultiSetLocalizationActivity.EXTRA_LOCALIZATION_MODE)
                    ?: MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE.name
            )
        } catch (e: IllegalArgumentException) {
            MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE
        }

        gpsTracker = GpsTracker(applicationContext) { model.onGpsFix(it) }
        runStatus = RunStatusIndicator { binding.runStatusCard.setState(it) }

        setupMap()
        setupSheet()
        applyChromeInsets()
        setupAR()
        setupControls()
        setupGeoHint()
        observeState()
        requestLocation()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(MapStyle.STREET.tileSource())
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    // A pan must move the map, not drag the sheet it sits in.
                    MotionEvent.ACTION_DOWN -> sheet.isDraggable = false
                    MotionEvent.ACTION_MOVE -> model.setFollow(false)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sheet.isDraggable = !isFullscreen
                }
                false
            }
        }
        binding.attributionText.text = MapStyle.STREET.attribution
        renderer = GeoPoseMapRenderer(this, binding.mapView)
    }

    private fun setupSheet() {
        sheet = BottomSheetBehavior.from(binding.bottomSheet)
        sheet.isFitToContents = false
        sheet.halfExpandedRatio = 0.55f
        sheet.isHideable = false
        sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        sheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                val open = newState != BottomSheetBehavior.STATE_COLLAPSED
                binding.mapControlsStart.isVisible = open
                binding.mapControlsEnd.isVisible = open
                binding.attributionText.isVisible = open
                updateMapCenterOffset()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = updateMapCenterOffset()
        })
        binding.mapView.post { updateMapCenterOffset() }
    }

    /**
     * The sheet reaches below the screen while half open, so the map's own centre is out of sight.
     * This moves it to the middle of the part that shows, for re-centre and follow.
     */
    private fun updateMapCenterOffset() {
        val map = binding.mapView
        if (map.height == 0) return
        val mapTop = IntArray(2).also { map.getLocationOnScreen(it) }[1]
        val rootTop = IntArray(2).also { binding.root.getLocationOnScreen(it) }[1]
        val visibleHeight = (rootTop + binding.root.height - mapTop).coerceIn(0, map.height)
        map.setMapCenterOffset(0, (visibleHeight - map.height) / 2)
    }

    /** AR chrome and map controls clear the bars while the camera and the map stay full-bleed. */
    private fun applyChromeInsets() {
        binding.topLeftColumn.marginForSystemBars(top = true)
        binding.topActions.marginForSystemBars(top = true)
        binding.mapControlsStart.marginForSystemBars(bottom = true) { watermarkClearance }
        binding.mapControlsEnd.marginForSystemBars(bottom = true)
        binding.attributionText.marginForSystemBars(bottom = true)

        val peekHeight = resources.getDimensionPixelSize(R.dimen.geopose_sheet_peek_height)
        ViewCompat.setOnApplyWindowInsetsListener(binding.dragHandleBar) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            sheet.peekHeight = peekHeight + bars.bottom
            sheet.expandedOffset = bars.top
            insets
        }

        MultiSetWatermark.attach(this) { clearance ->
            watermarkClearance = clearance
            ViewCompat.requestApplyInsets(binding.mapControlsStart)
        }
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
                            buildSession()
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
        (arFragment.view as? ViewGroup)?.let(::hideInstructions)
    }

    private fun hideInstructions(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = try { resources.getResourceEntryName(child.id) } catch (e: Exception) { "" }
            if (name.contains("instruction", ignoreCase = true) || name.contains("hand", ignoreCase = true)) {
                child.visibility = View.GONE
            }
            if (child is ViewGroup) hideInstructions(child)
        }
    }

    private fun buildSession() {
        val source = ArFrameSource(arFragment, this, ImageProcessor()).also { frameSource = it }

        localizationSession = MultiSetSDK.localizationSession(source, localizationMode).apply {
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
            // The whole screen is the GeoPose, so it is asked for whatever the Settings toggle says.
            includeGeoCoordinatesInResponse = true

            onLocalizationRequested = {
                onUi {
                    model.onLocalizationRequested()
                    showStatus(getString(R.string.localizing_status))
                }
            }

            onStageChanged = { stage, isBackground -> onUi { renderRun(stage, isBackground) } }

            onRecoveryPhaseChanged = { phase, _ ->
                onUi {
                    when (phase) {
                        RecoveryPhase.WAITING_FOR_TRACKING -> R.string.recovery_waiting
                        RecoveryPhase.REALIGNING -> R.string.recovery_realigning
                        RecoveryPhase.NONE -> null
                    }?.let { showStatus(getString(it)) }
                }
            }

            onLocalizationSuccess = { result ->
                onUi {
                    runStatus?.receive(RunStatusState.Succeeded)
                    val point = model.onLocalized(result, System.currentTimeMillis())
                    showStatus(getString(R.string.localized_map, result.mapCode))
                    Log.d(TAG, "Localized on ${result.mapCode}, confidence ${result.confidence}, GeoPose ${result.geoPose}")
                    if (LocalizationConfig.showAlerts) {
                        toast(getString(if (point != null) R.string.localization_success else R.string.geopose_missing_toast))
                    }
                }
            }

            onLocalizationFalsePositive = { info ->
                onUi {
                    runStatus?.receive(RunStatusState.Failed)
                    model.onLocalizationFailed()
                    showStatus(getString(R.string.false_positive_status))
                    Log.w(TAG, "Discarded ${info.summary}")
                }
            }

            onLocalizationFailure = { error ->
                onUi {
                    runStatus?.receive(RunStatusState.Failed)
                    model.onLocalizationFailed()
                    showStatus(getString(R.string.localization_failed))
                    if (!error.isTransient) {
                        failureAlert.show(this@GeoPoseMapActivity, error)
                    } else if (LocalizationConfig.showAlerts) {
                        toast(getString(R.string.localization_failed))
                    }
                    Log.e(TAG, "Localization failure: ${error.kind} — ${error.message}")
                }
            }
        }

        // Capture before ARCore is TRACKING fails with "Failed to capture frame"; see onSceneUpdate.
        pendingAutoStart = LocalizationConfig.autoLocalize
    }

    private fun onUi(action: () -> Unit) = runOnUiThread {
        if (!isDestroyed && !isFinishing) action()
    }

    private fun showStatus(text: String) {
        binding.statusText.text = text
        binding.statusOverlay.visibility = View.VISIBLE
    }

    /** No overlay here: the map must stay readable, so a run's stage goes to the status chip. */
    private fun renderRun(stage: LocalizationStage, isBackground: Boolean) {
        val session = localizationSession ?: return
        if (stage == LocalizationStage.Idle && session.isAwaitingRetry) {
            runStatus?.keepOpen()
        } else {
            runStatus?.receive(RunStatusState.from(stage))
        }
        if (stage != LocalizationStage.Idle && !isBackground) {
            showStatus(RunStatusCopy.captureStatus(stage) ?: RunStatusCopy.serverWaitText(stage, objectTracking = false))
        }
        updateLocalizeButton()
    }

    private fun updateLocalizeButton() {
        val session = localizationSession
        val enabled = lastTrackingState == TrackingState.TRACKING && session != null && !session.isBusy
        binding.localizeButton.isEnabled = enabled
        binding.localizeButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun onSceneUpdate() {
        val frame = arFragment.arSceneView.arFrame ?: return
        val currentState = frame.camera.trackingState

        if (currentState == TrackingState.TRACKING && pendingAutoStart && !sessionStarted) {
            pendingAutoStart = false
            sessionStarted = true
            localizationSession?.start()
        }

        val reason = frame.camera.trackingFailureReason
        // A reason can change while the state does not, e.g. poor features turning into a lost camera.
        if (currentState != lastTrackingState || reason != lastFailureReason) {
            lastTrackingState = currentState
            lastFailureReason = reason
            localizationSession?.reportArTracking(currentState, reason)
            updateLocalizeButton()
        }
    }

    private fun setupControls() {
        binding.closeButton.setOnClickListener { finish() }
        binding.resetButton.setOnClickListener { resetLocalization() }
        binding.localizeButton.setOnClickListener {
            localizationSession?.let {
                failureAlert.reset()
                it.start()
                sessionStarted = true
            }
        }
        binding.recenterButton.setOnClickListener {
            model.setFollow(true)
            renderer.target(model.state.value)?.let { binding.mapView.controller.animateTo(it) }
        }
        binding.zoomInButton.setOnClickListener { binding.mapView.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.mapView.controller.zoomOut() }
        binding.fullscreenButton.setOnClickListener { toggleFullscreen() }
        binding.mapStyleButton.setOnClickListener { showMapStyleMenu(it) }
        updateLocalizeButton()
    }

    private fun showMapStyleMenu(anchor: View) {
        val labels = mapOf(
            MapStyle.STREET to R.string.geopose_style_street,
            MapStyle.SATELLITE to R.string.geopose_style_satellite,
            MapStyle.LIGHT to R.string.geopose_style_light,
            MapStyle.DARK to R.string.geopose_style_dark,
        )
        PopupMenu(this, anchor).apply {
            MapStyle.entries.forEachIndexed { index, style -> menu.add(0, index, index, labels.getValue(style)) }
            setOnMenuItemClickListener { item ->
                val style = MapStyle.entries[item.itemId]
                binding.mapView.setTileSource(style.tileSource())
                binding.attributionText.text = style.attribution
                true
            }
            show()
        }
    }

    /** The way out of a wrong first fix: the session forgets its anchor and localizes afresh. */
    private fun resetLocalization() {
        failureAlert.reset()
        localizationSession?.resetSession()
        showStatus(getString(R.string.initializing_status))
        sessionStarted = false
        pendingAutoStart = LocalizationConfig.autoLocalize
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        sheet.isDraggable = !isFullscreen
        sheet.state = if (isFullscreen) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED
        binding.fullscreenButton.setImageResource(if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        binding.fullscreenButton.contentDescription =
            getString(if (isFullscreen) R.string.geopose_exit_full_screen else R.string.geopose_full_screen)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    centerOnFirstPlace(state)
                    renderer.render(state)
                    renderGeoPose(state)
                    renderNotice(state)
                }
            }
        }
    }

    // Until a place is known the map sits at 0°, 0°; the first one is shown without the animation.
    private fun centerOnFirstPlace(state: GeoPoseMapState) {
        if (mapCentered) return
        val place = renderer.target(state) ?: return
        mapCentered = true
        binding.mapView.controller.setCenter(place)
    }

    private fun renderGeoPose(state: GeoPoseMapState) {
        val point = state.latestGeoPose
        binding.geoPoseHeading.text = point?.headingDegrees
            ?.let { getString(R.string.geopose_heading, it, GeoPoseMath.cardinal(it)) }
            ?: getString(R.string.geopose_heading_none)
        binding.geoPoseCoordinates.text = when {
            point != null -> String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
            state.status == GeoPoseStatus.NOT_GEOREFERENCED -> getString(R.string.geopose_none)
            else -> getString(R.string.geopose_waiting)
        }
        binding.geoPoseMeta.isVisible = point != null
        if (point != null) {
            val parts = listOfNotNull(
                point.altitude?.let { getString(R.string.geopose_altitude, it) },
                point.confidence?.let { getString(R.string.geopose_confidence, it) },
                point.mapCodes.firstOrNull(),
            )
            binding.geoPoseMeta.text = parts.joinToString(" · ")
        }
    }

    private fun renderNotice(state: GeoPoseMapState) {
        val notice = when {
            state.status == GeoPoseStatus.NOT_GEOREFERENCED -> R.string.geopose_notice_not_georeferenced
            !gpsTracker.hasPermission -> R.string.geopose_notice_permission
            !gpsTracker.isListening && state.latestGps == null -> R.string.geopose_notice_location_off
            gpsTracker.coarseOnly -> R.string.geopose_notice_coarse
            else -> null
        }
        binding.noticeText.isVisible = notice != null
        notice?.let { binding.noticeText.setText(it) }
    }

    private fun requestLocation() {
        if (gpsTracker.hasPermission) return
        locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** The GPS hint is the Settings toggle's; the indicator says whether a fix is going out. */
    private fun setupGeoHint() {
        if (!LocalizationConfig.enableGeoHint) return
        geoHintProvider = GeoHintProvider { gpsTracker.currentCoordinates(GEO_HINT_MAX_AGE_MS) }
            .also { MultiSetSDK.geoHintProvider = it }
        binding.gpsIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val hasFix = gpsTracker.currentCoordinates(GEO_HINT_MAX_AGE_MS)?.isValid() == true
                    binding.gpsStatusDot.setBackgroundResource(if (hasFix) R.drawable.gps_dot_active else R.drawable.gps_dot_inactive)
                    delay(GPS_REFRESH_MS)
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        gpsTracker.start()
        renderNotice(model.state.value)
    }

    /**
     * ARCore cannot reset its world frame, and one resumed after a trip to the background may no
     * longer match the room, so the fix is treated as stale and the SDK re-localizes once tracking
     * is back. Ahead of super.onPause(), which pauses the AR session.
     */
    override fun onPause() {
        frameSource?.markFramesStale()
        if (sessionStarted) {
            localizationSession?.reportTrackingState(SdkTrackingState.PAUSED)
            localizationSession?.notifyWorldOriginReset()
        }
        lastTrackingState = TrackingState.PAUSED
        lastFailureReason = null
        gpsTracker.stop()
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        failureAlert.dismiss()
        runStatus?.release()
        localizationSession?.stop()
        localizationSession = null
        if (MultiSetSDK.geoHintProvider === geoHintProvider) MultiSetSDK.geoHintProvider = null
        binding.mapView.onDetach()
    }
}
