/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.mapping

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.mapping.MappingAutoStopReason
import com.multiset.sdk.mapping.MappingDraft
import com.multiset.sdk.mapping.MappingFailure
import com.multiset.sdk.mapping.MappingLimits
import com.multiset.sdk.mapping.MappingPhase
import com.multiset.sdk.mapping.MappingScanState
import com.multiset.sdk.mapping.MappingStopOutcome
import com.multiset.sdk.mapping.MappingTrackingStatus
import com.multiset.sdk.mapping.MappingUploadState
import com.multiset.sdk.mapping.MultiSetMapping
import com.multiset.sdk.ui.MultiSetWatermark
import com.multiset.xr.R
import com.multiset.xr.config.ConfigStore
import com.multiset.xr.databinding.ActivityMapScanBinding
import com.multiset.xr.mapping.AndroidLocationProvider
import com.multiset.xr.mapping.ArMappingSource
import com.multiset.xr.mapping.MappingSessionConfig
import com.multiset.xr.ui.MeshToggle
import com.multiset.xr.ui.marginForSystemBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The landscape map scan: a camera preview with the coverage cloud, a shutter that records, and the name card,
 * draft and upload that follow a finished scan. [MultiSetSDK.mapping] drives the scan; this screen renders it.
 */
class MapScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapScanBinding
    private lateinit var arFragment: ArFragment
    private lateinit var mapping: MultiSetMapping
    private lateinit var source: ArMappingSource
    private lateinit var pointsToggle: MeshToggle

    private val viewMatrix = FloatArray(MATRIX_SIZE)
    private val projectionMatrix = FloatArray(MATRIX_SIZE)
    private val dialogs = mutableListOf<AlertDialog>()

    private var scan: MappingScanState? = null
    private var shownFailure: MappingFailure? = null
    private var interruptionAnnounced = false
    private var nameCardShown = false
    private var saving = false
    private var followedId: String? = null
    private var followedName = ""
    private var followedEntrySeen = false
    private var recordingLook: Boolean? = null
    private var statusIconShown: Int? = NO_ICON
    private var locationGate = LocationGate.UNCHECKED
    private var watermarkClearance = 0

    // Any answer settles the gate: location only geo-tags the map
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { settleLocation() }

    private val attached: Boolean get() = ::source.isInitialized

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMapScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A process restored straight into this screen has not run MultiSetSDK.initialize
        val sdkMapping = runCatching { MultiSetSDK.mapping }.getOrNull()
        val fragment = supportFragmentManager.findFragmentById(R.id.arFragment) as? ArFragment
        if (sdkMapping == null || fragment == null) {
            finish()
            return
        }
        mapping = sdkMapping
        arFragment = fragment
        // Set before the fragment resumes, when it creates and configures the ARCore session
        arFragment.setOnSessionConfigurationListener { session, config ->
            source.onDepthSupport(MappingSessionConfig.apply(session, config))
        }
        source = ArMappingSource(
            arFragment,
            pointSink = binding.pointView,
            // The SDK can hold the provider after this screen is gone, so it must not hold the activity
            locationProvider = AndroidLocationProvider(applicationContext),
            onTrackingSample = mapping::reportTrackingSample,
        )
        attachedSource = WeakReference(source)
        mapping.setSource(source)
        mapping.beginPreview()

        arFragment.viewLifecycleOwnerLiveData.observe(this) { owner ->
            val sceneView = arFragment.arSceneView
            if (owner == null || sceneView == null) return@observe
            sceneView.planeRenderer.isEnabled = false
            sceneView.planeRenderer.isVisible = false
            arFragment.instructionsController?.isEnabled = false
            sceneView.scene.addOnUpdateListener { onSceneUpdate() }
        }

        savedInstanceState?.let(::restore)
        applyChromeInsets()
        setUpControls()
        onBackPressedDispatcher.addCallback(this) { onBack() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { mapping.scan.collect(::render) }
                launch {
                    combine(mapping.uploads, mapping.drafts) { uploads, drafts -> uploads to drafts }
                        .collect { (uploads, drafts) -> renderUpload(uploads, drafts) }
                }
            }
        }
    }

    private fun onSceneUpdate() {
        val frame = arFragment.arSceneView?.arFrame ?: return
        source.onSceneUpdate(frame)
        val camera = frame.camera
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP, FAR_CLIP)
        binding.pointView.setCamera(viewMatrix, projectionMatrix)
        // Checked once the camera runs, so the prompt never races the fragment's camera request
        if (locationGate == LocationGate.UNCHECKED) checkLocation()
    }

    // The prompt pauses the screen, which interrupts a scan, so record waits until it is answered
    private fun checkLocation() {
        val missing = LOCATION_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty() || ConfigStore.isMappingLocationAsked(this)) return settleLocation()
        ConfigStore.setMappingLocationAsked(this, true)
        locationGate = LocationGate.ASKING
        locationPermission.launch(missing.toTypedArray())
    }

    private fun settleLocation() {
        locationGate = LocationGate.SETTLED
        scan?.let(::renderRecordButton)
    }

    private fun applyChromeInsets() {
        binding.topStart.marginForSystemBars(top = true, sides = true)
        binding.topEnd.marginForSystemBars(top = true, sides = true)
        binding.bottomStart.marginForSystemBars(bottom = true, sides = true) { watermarkClearance }
        binding.recordButton.marginForSystemBars(bottom = true, sides = true)
        binding.packagingPanel.marginForSystemBars(bottom = true)
        listOf(binding.nameCardOverlay, binding.uploadOverlay).forEach { overlay ->
            ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
                val clear = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime(),
                )
                view.updatePadding(left = clear.left, top = clear.top, right = clear.right, bottom = clear.bottom)
                insets
            }
        }
        MultiSetWatermark.attach(this) { clearance ->
            watermarkClearance = clearance
            ViewCompat.requestApplyInsets(binding.bottomStart)
        }
    }

    private fun setUpControls() {
        binding.backButton.setOnClickListener { onBack() }
        binding.recordButton.setOnClickListener { onRecord() }
        binding.finalMinuteBanner.text = MapScanCopy.FINAL_MINUTE
        binding.finalMinuteBanner.leadingIcon(R.drawable.ic_timer, android.R.color.black)
        binding.motionBanner.text = MapScanCopy.MOVE_SLOWER
        binding.motionBanner.leadingIcon(R.drawable.ic_warning, android.R.color.black)

        binding.pointsToggle.text = MapScanCopy.POINTS
        pointsToggle = MeshToggle(
            binding.pointsToggle,
            describe = { on -> if (on) MapScanCopy.POINTS_SHOWN else MapScanCopy.POINTS_HIDDEN },
        ) { shown -> binding.pointView.isInvisible = !shown }

        binding.nameTitle.text = MapScanCopy.NAME_TITLE
        binding.nameCloseButton.contentDescription = MapScanCopy.CLOSE
        binding.nameField.hint = MapScanCopy.NAME_HINT
        binding.submitButton.text = MapScanCopy.SUBMIT
        binding.saveLaterButton.text = MapScanCopy.SAVE_FOR_LATER
        binding.nameField.doAfterTextChanged { renderNameCard() }
        binding.nameField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) hideKeyboard()
            false
        }
        binding.nameCloseButton.setOnClickListener { hideNameCard() }
        binding.submitButton.setOnClickListener { save(thenUpload = true) }
        binding.saveLaterButton.setOnClickListener { save(thenUpload = false) }
        binding.uploadDoneButton.setOnClickListener { closeUploadCard() }
        renderNameCard()
    }

    private fun render(state: MappingScanState) {
        val previous = scan
        scan = state
        val phase = state.phase
        val recording = phase == MappingPhase.Mapping

        binding.backButton.isEnabled = !state.isBusy
        binding.backButton.alpha = if (state.isBusy) DISABLED_ALPHA else 1f

        binding.guidancePanel.isVisible = recording
        binding.framesKeptText.text = MapScanCopy.framesKept(state.savedFrames)
        val issue = MapScanCopy.tracking(state.tracking)
        binding.trackingIssueText.isVisible = issue != null
        if (issue != null) {
            binding.trackingIssueText.text = issue
            val tint = if (state.tracking?.level == MappingTrackingStatus.Level.LOST) R.color.ar_bad else R.color.ar_warning
            binding.trackingIssueText.setTextColor(ContextCompat.getColor(this, tint))
        }

        renderTimer(state)
        val tracking = state.tracking
        binding.trackingChip.isVisible = tracking != null
        if (tracking != null) {
            binding.trackingLevelText.text = MapScanCopy.trackingLevel(tracking.level)
            binding.trackingDot.backgroundTintList = ContextCompat.getColorStateList(this, levelColor(tracking.level))
        }

        binding.finalMinuteBanner.isVisible = MapScanCopy.isInFinalMinute(state)
        binding.motionBanner.isVisible = state.showsMotionWarning

        val zipping = phase == MappingPhase.Zipping
        binding.packagingPanel.isVisible = zipping
        if (zipping) {
            binding.packagingText.text = MapScanCopy.packaging(state.packagingProgress)
            binding.packagingProgress.setProgressCompat((state.packagingProgress * PROGRESS_MAX).toInt(), false)
        }
        binding.bottomStart.isVisible = !zipping
        binding.statusText.text = MapScanCopy.status(state)
        val icon = statusIcon(phase)
        if (icon != statusIconShown) {
            statusIconShown = icon
            binding.statusText.leadingIcon(icon, R.color.ar_on)
        }
        pointsToggle.setAvailable(phase == MappingPhase.Preparing || recording)
        renderRecordButton(state)

        if (phase == MappingPhase.Finished && previous?.phase != MappingPhase.Finished && followedId == null) {
            showNameCard()
        }
        if (phase != MappingPhase.Finished && nameCardShown) hideNameCard()
        if (phase is MappingPhase.Failed && phase.failure !== shownFailure) showFailure(phase.failure)
        announceInterruption(previous, state)
    }

    private fun renderTimer(state: MappingScanState) {
        val phase = state.phase
        val shows = phase == MappingPhase.Mapping || phase == MappingPhase.Zipping || phase == MappingPhase.Finished
        binding.timerPanel.isVisible = shows
        if (!shows) return
        val elapsed = state.elapsedMillis
        binding.timerText.text = MapScanCopy.timer(elapsed)
        binding.timerPanel.contentDescription = MapScanCopy.timerDescription(elapsed)
        binding.recordingDot.isVisible = phase == MappingPhase.Mapping
        binding.recordingDot.alpha = if ((elapsed / BLINK_HALF_PERIOD_MS) % 2 == 0L) 1f else BLINK_DIM_ALPHA
        val fraction = (elapsed.toDouble() / MAX_MILLIS).coerceAtMost(1.0)
        binding.timerProgress.setProgressCompat((fraction * PROGRESS_MAX).toInt(), false)
        val belowMinimum = elapsed < MappingLimits.MINIMUM_SCAN_DURATION_SECONDS * MILLIS_PER_SECOND
        binding.timerProgress.setIndicatorColor(
            ContextCompat.getColor(this, if (belowMinimum) R.color.ar_warning else R.color.ar_brand),
        )
    }

    private fun renderRecordButton(state: MappingScanState) {
        val phase = state.phase
        val recording = phase == MappingPhase.Mapping
        binding.recordButton.isVisible = phase != MappingPhase.Zipping && phase != MappingPhase.Finished
        val enabled = when {
            recording -> MapScanCopy.canFinish(state)
            phase == MappingPhase.Preparing -> false
            else -> state.canStart && locationGate == LocationGate.SETTLED
        }
        binding.recordButton.isEnabled = enabled
        binding.recordButton.alpha = if (enabled) 1f else DISABLED_ALPHA
        binding.recordButton.contentDescription = when {
            !recording -> MapScanCopy.START_RECORDING
            enabled -> MapScanCopy.STOP_RECORDING
            else -> "${MapScanCopy.STOP_RECORDING}. ${MapScanCopy.STOP_HINT}"
        }
        if (recordingLook == recording) return
        recordingLook = recording
        binding.recordGlow.setBackgroundResource(
            if (recording) R.drawable.bg_record_glow_recording else R.drawable.bg_record_glow,
        )
        binding.recordMark.setBackgroundResource(if (recording) R.drawable.bg_record_square else R.drawable.bg_record_disc)
        val size = dp(if (recording) RECORD_SQUARE_DP else RECORD_DISC_DP)
        binding.recordMark.updateLayoutParams {
            width = size
            height = size
        }
    }

    private fun onRecord() {
        val state = scan ?: return
        when {
            state.phase == MappingPhase.Mapping -> if (MapScanCopy.canFinish(state)) stop()
            state.canStart && state.phase != MappingPhase.Finished && locationGate == LocationGate.SETTLED ->
                mapping.startScan()
        }
    }

    private fun stop() {
        lifecycleScope.launch {
            if (mapping.stopScan() == MappingStopOutcome.DISCARDED_TOO_SHORT) showTooShort()
        }
    }

    // An interrupted scan is announced once: kept (stopped) or dropped (too short)
    private fun announceInterruption(previous: MappingScanState?, state: MappingScanState) {
        if (state.autoStopReason != MappingAutoStopReason.INTERRUPTED) {
            interruptionAnnounced = false
            return
        }
        if (interruptionAnnounced) return
        when (state.phase) {
            MappingPhase.Zipping, MappingPhase.Finished -> {
                interruptionAnnounced = true
                MapScanCopy.stopped(MappingAutoStopReason.INTERRUPTED)?.let { alert(MapScanCopy.STOPPED_TITLE, it) }
            }
            // Idle on first sight is a scan saved or dropped before this screen opened
            MappingPhase.Idle -> {
                interruptionAnnounced = true
                if (previous != null) showTooShort()
            }
            else -> Unit
        }
    }

    private fun onBack() {
        when {
            binding.uploadOverlay.isVisible -> closeUploadCard()
            nameCardShown -> hideNameCard()
            else -> when (scan?.phase) {
                MappingPhase.Preparing, MappingPhase.Mapping -> confirm(
                    MapScanCopy.DISCARD_RECORDING_MESSAGE,
                    positive = MapScanCopy.DISCARD to ::discardAndClose,
                    negative = MapScanCopy.KEEP_SCANNING to {},
                )
                MappingPhase.Finished -> confirm(
                    MapScanCopy.DISCARD_FINISHED_MESSAGE,
                    positive = MapScanCopy.NAME_AND_SAVE to ::showNameCard,
                    negative = MapScanCopy.CANCEL to {},
                    neutral = MapScanCopy.DISCARD to ::discardAndClose,
                )
                MappingPhase.Zipping -> Unit
                else -> discardAndClose()
            }
        }
    }

    private fun discardAndClose() {
        lifecycleScope.launch {
            mapping.discardScan()
            finish()
        }
    }

    private fun showNameCard() {
        nameCardShown = true
        binding.nameCardOverlay.isVisible = true
        renderNameCard()
    }

    private fun hideNameCard() {
        nameCardShown = false
        binding.nameCardOverlay.isVisible = false
        hideKeyboard()
    }

    private fun renderNameCard() {
        val name = binding.nameField.text?.toString().orEmpty()
        val error = MapScanCopy.nameError(name)
        binding.nameError.isVisible = error != null
        binding.nameError.text = error
        val enabled = MapScanCopy.isValidName(name) && !saving
        binding.submitButton.isEnabled = enabled
        binding.saveLaterButton.isEnabled = enabled
    }

    // The draft is saved first either way, so an upload that fails cannot cost the scan
    private fun save(thenUpload: Boolean) {
        val name = binding.nameField.text?.toString().orEmpty()
        if (saving || !MapScanCopy.isValidName(name)) return
        saving = true
        renderNameCard()
        hideKeyboard()
        lifecycleScope.launch {
            try {
                val draft = mapping.saveDraft(name)
                hideNameCard()
                if (thenUpload) follow(draft) else finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alert(MapScanCopy.SAVE_ERROR_TITLE, e.message.orEmpty())
            } finally {
                saving = false
                renderNameCard()
            }
        }
    }

    private fun follow(draft: MappingDraft) {
        followedId = draft.id
        followedName = draft.name
        followedEntrySeen = false
        mapping.upload(draft.id)
        renderUpload(mapping.uploads.value, mapping.drafts.value)
    }

    // Success: the entry, once seen, and the draft are both gone; a list still loading cannot pass
    private fun renderUpload(uploads: Map<String, MappingUploadState>, drafts: List<MappingDraft>) {
        val id = followedId
        binding.uploadOverlay.isVisible = id != null
        if (id == null) return
        val state = uploads[id]
        if (state != null) followedEntrySeen = true
        if (state == null && followedEntrySeen && drafts.none { it.id == id }) {
            followedId = null
            binding.uploadOverlay.isVisible = false
            showUploaded(followedName)
            return
        }
        bindUploadCard(state)
    }

    private fun bindUploadCard(state: MappingUploadState?) {
        val uploading = state as? MappingUploadState.Uploading
        binding.uploadSpinner.isVisible = state == null || state == MappingUploadState.Preparing
        binding.uploadProgress.isVisible = uploading != null
        uploading?.let { binding.uploadProgress.setProgressCompat((it.fraction * PROGRESS_MAX).toInt(), false) }
        val (title, detail) = when (state) {
            null, MappingUploadState.Preparing -> MapScanCopy.uploadPreparing(followedName) to null
            is MappingUploadState.Uploading ->
                MapScanCopy.uploadUploading(followedName) to MapScanCopy.uploadPercent(state.fraction)
            is MappingUploadState.Waiting -> MapScanCopy.UPLOAD_QUEUED_TITLE to MapScanCopy.uploadQueued(state.reason)
            is MappingUploadState.Failed -> MapScanCopy.UPLOAD_FAILED_TITLE to MapScanCopy.uploadFailed(state.message)
        }
        binding.uploadTitle.text = title
        binding.uploadDetail.isVisible = detail != null
        binding.uploadDetail.text = detail
        val outcome = state is MappingUploadState.Waiting || state is MappingUploadState.Failed
        binding.uploadIcon.isVisible = outcome
        binding.uploadDoneButton.isVisible = outcome
        if (state is MappingUploadState.Waiting) {
            binding.uploadIcon.setImageResource(R.drawable.ic_history)
            binding.uploadIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.ar_on_mid)
            binding.uploadDoneButton.text = MapScanCopy.DONE
        } else if (state is MappingUploadState.Failed) {
            binding.uploadIcon.setImageResource(R.drawable.ic_warning)
            binding.uploadIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.ar_bad)
            binding.uploadDoneButton.text = MapScanCopy.OK
        }
    }

    // Leaving keeps the upload going; only a failure the user has now seen is cleared
    private fun closeUploadCard() {
        followedId?.let(mapping::dismissUploadFailure)
        followedId = null
        binding.uploadOverlay.isVisible = false
        finish()
    }

    // Acknowledged on OK or cancel, not on dismiss: a dialog closed by onDestroy was never read
    private fun showFailure(failure: MappingFailure) {
        shownFailure = failure
        dialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(MapScanCopy.FAILED_TITLE)
                .setMessage(failure.message)
                .setPositiveButton(MapScanCopy.OK) { _, _ -> mapping.acknowledgeFailure() }
                .setOnCancelListener { mapping.acknowledgeFailure() },
        )
    }

    private fun showTooShort() = dialog(
        MaterialAlertDialogBuilder(this)
            .setTitle(MapScanCopy.TOO_SHORT_TITLE)
            .setMessage(MapScanCopy.TOO_SHORT_MESSAGE)
            .setPositiveButton(MapScanCopy.SCAN_AGAIN, null),
    )

    private fun showUploaded(name: String) = dialog(
        MaterialAlertDialogBuilder(this)
            .setTitle(MapScanCopy.UPLOADED_TITLE)
            .setMessage(MapScanCopy.uploaded(name))
            .setPositiveButton(MapScanCopy.DONE) { _, _ -> finish() }
            .setOnCancelListener { finish() },
    )

    private fun alert(title: String, message: String) = dialog(
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(MapScanCopy.OK, null),
    )

    private fun confirm(
        message: String,
        positive: Pair<String, () -> Unit>,
        negative: Pair<String, () -> Unit>,
        neutral: Pair<String, () -> Unit>? = null,
    ) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(MapScanCopy.DISCARD_TITLE)
            .setMessage(message)
            .setPositiveButton(positive.first) { _, _ -> positive.second() }
            .setNegativeButton(negative.first) { _, _ -> negative.second() }
        neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        dialog(builder)
    }

    private fun dialog(builder: MaterialAlertDialogBuilder) {
        dialogs.removeAll { !it.isShowing }
        dialogs += builder.show()
    }

    private fun hideKeyboard() {
        WindowCompat.getInsetsController(window, binding.nameField).hide(WindowInsetsCompat.Type.ime())
        binding.nameField.clearFocus()
    }

    private fun TextView.leadingIcon(@DrawableRes icon: Int?, @ColorRes tint: Int) {
        val drawable = icon?.let { AppCompatResources.getDrawable(context, it)?.mutate() }?.apply {
            val size = dp(ICON_DP)
            setBounds(0, 0, size, size)
            setTint(ContextCompat.getColor(context, tint))
        }
        setCompoundDrawablesRelative(drawable, null, null, null)
    }

    @DrawableRes
    private fun statusIcon(phase: MappingPhase): Int? = when (phase) {
        MappingPhase.Idle, is MappingPhase.Failed, MappingPhase.Mapping -> R.drawable.ic_record
        MappingPhase.Preparing -> R.drawable.ic_sync
        MappingPhase.Finished -> R.drawable.ic_check_circle
        MappingPhase.Zipping -> null
    }

    @ColorRes
    private fun levelColor(level: MappingTrackingStatus.Level): Int = when (level) {
        MappingTrackingStatus.Level.GOOD -> R.color.ar_good
        MappingTrackingStatus.Level.WEAK -> R.color.ar_warning
        MappingTrackingStatus.Level.LOST -> R.color.ar_bad
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun restore(state: Bundle) {
        followedId = state.getString(KEY_FOLLOWED_ID)
        followedName = state.getString(KEY_FOLLOWED_NAME).orEmpty()
        followedEntrySeen = state.getBoolean(KEY_FOLLOWED_SEEN)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FOLLOWED_ID, followedId)
        outState.putString(KEY_FOLLOWED_NAME, followedName)
        outState.putBoolean(KEY_FOLLOWED_SEEN, followedEntrySeen)
    }

    // Before super.onPause(), which pauses the AR session: capture must stop reading it first
    override fun onPause() {
        if (attached) mapping.reportSourceInterrupted()
        super.onPause()
    }

    // A newer scan screen may have attached its own source already; only the owner detaches
    override fun onDestroy() {
        dialogs.filter(AlertDialog::isShowing).forEach(AlertDialog::dismiss)
        dialogs.clear()
        if (attached && attachedSource?.get() === source) {
            mapping.endPreview()
            mapping.setSource(null)
            attachedSource = null
        }
        super.onDestroy()
    }

    private companion object {
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        const val KEY_FOLLOWED_ID = "followedDraftId"
        const val KEY_FOLLOWED_NAME = "followedDraftName"
        const val KEY_FOLLOWED_SEEN = "followedEntrySeen"
        const val MATRIX_SIZE = 16
        const val NEAR_CLIP = 0.1f
        const val FAR_CLIP = 100f
        const val PROGRESS_MAX = 1000
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_MILLIS = MappingLimits.MAXIMUM_SCAN_DURATION_SECONDS * MILLIS_PER_SECOND
        const val BLINK_HALF_PERIOD_MS = 500L
        const val BLINK_DIM_ALPHA = 0.3f
        const val DISABLED_ALPHA = 0.5f
        const val RECORD_DISC_DP = 54
        const val RECORD_SQUARE_DP = 26
        const val ICON_DP = 16
        const val NO_ICON = -1

        var attachedSource: WeakReference<ArMappingSource>? = null
    }

    private enum class LocationGate { UNCHECKED, ASKING, SETTLED }
}
