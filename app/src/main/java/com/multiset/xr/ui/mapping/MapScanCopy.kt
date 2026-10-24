/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.mapping

import com.multiset.sdk.mapping.MappingAutoStopReason
import com.multiset.sdk.mapping.MappingError
import com.multiset.sdk.mapping.MappingLimits
import com.multiset.sdk.mapping.MappingPhase
import com.multiset.sdk.mapping.MappingScanState
import com.multiset.sdk.mapping.MappingTrackingStatus
import com.multiset.sdk.mapping.MappingTrackingStatus.Issue
import com.multiset.sdk.mapping.MappingTrackingStatus.Level
import java.util.Locale
import kotlin.math.ceil

/** What the map scan screen says, kept out of the activity so tests can pin it. */
object MapScanCopy {
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60
    private const val PERCENT = 100
    private const val MIN_MILLIS = MappingLimits.MINIMUM_SCAN_DURATION_SECONDS * MILLIS_PER_SECOND
    private const val MAX_MILLIS = MappingLimits.MAXIMUM_SCAN_DURATION_SECONDS * MILLIS_PER_SECOND
    private const val FINAL_MINUTE_MILLIS = MAX_MILLIS - SECONDS_PER_MINUTE * MILLIS_PER_SECOND

    const val IDLE = "Tap record to start scanning"
    const val PREPARING = "Finding your position…"
    const val RECORDING = "Recording"
    const val MOVE_SLOWER = "Move slower"
    val FINAL_MINUTE = "1 minute left — the scan stops at ${elapsed(MAX_MILLIS)}"
    const val STOP_HINT = "Available after ten seconds"
    const val POINTS = "Points"
    const val POINTS_SHOWN = "Points shown"
    const val POINTS_HIDDEN = "Points hidden"
    const val START_RECORDING = "Start recording"
    const val STOP_RECORDING = "Stop recording"
    const val CLOSE = "Close"

    const val DISCARD_TITLE = "Discard this scan?"
    const val DISCARD_RECORDING_MESSAGE = "Nothing captured so far will be kept."
    const val DISCARD_FINISHED_MESSAGE = "This scan has not been saved. Discarding it cannot be undone."
    const val DISCARD = "Discard"
    const val KEEP_SCANNING = "Keep scanning"
    const val NAME_AND_SAVE = "Name and save"
    const val CANCEL = "Cancel"
    const val TOO_SHORT_TITLE = "Scan too short"
    val TOO_SHORT_MESSAGE = "A scan needs at least ${MappingLimits.MINIMUM_SCAN_DURATION_SECONDS} seconds to " +
        "build a map. This one has been discarded — start again and keep going."
    const val SCAN_AGAIN = "Scan again"
    const val FAILED_TITLE = "Scan failed"
    const val STOPPED_TITLE = "Scan stopped"
    const val SAVE_ERROR_TITLE = "Couldn't save the scan"
    const val OK = "OK"
    const val DONE = "Done"

    const val NAME_TITLE = "Enter Map Name"
    const val NAME_HINT = "Map name"
    const val SUBMIT = "Submit Map Data"
    const val SAVE_FOR_LATER = "Upload Later (Draft)"

    const val UPLOAD_QUEUED_TITLE = "Upload queued"
    const val UPLOAD_FAILED_TITLE = "Upload failed"
    const val UPLOADED_TITLE = "Map uploaded"

    /** "mm:ss", truncated to the second. */
    fun elapsed(millis: Long): String {
        val seconds = (millis / MILLIS_PER_SECOND).toInt()
        return String.format(Locale.US, "%02d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    }

    fun timer(elapsedMillis: Long): String = "${elapsed(elapsedMillis)} / ${elapsed(MAX_MILLIS)}"

    fun timerDescription(elapsedMillis: Long): String =
        "Scanning for ${elapsedMillis / MILLIS_PER_SECOND} seconds of a ten minute maximum"

    fun keepGoing(remainingSeconds: Int): String = "Keep going — $remainingSeconds s more"

    fun recording(elapsedMillis: Long): String = if (elapsedMillis < MIN_MILLIS) {
        keepGoing(ceil((MIN_MILLIS - elapsedMillis) / MILLIS_PER_SECOND.toDouble()).toInt())
    } else {
        RECORDING
    }

    fun packaging(progress: Double): String = "Packaging ${percent(progress)}%"

    /** The bottom-bar line for [state]'s phase. */
    fun status(state: MappingScanState): String = when (state.phase) {
        MappingPhase.Idle, is MappingPhase.Failed -> IDLE
        MappingPhase.Preparing -> PREPARING
        MappingPhase.Mapping -> recording(state.elapsedMillis)
        MappingPhase.Zipping -> packaging(state.packagingProgress)
        MappingPhase.Finished -> summary(state)
    }

    fun summary(state: MappingScanState): String = listOfNotNull(
        frames(state.savedFrames),
        "${state.blurredFrames} too blurred to keep".takeIf { state.blurredFrames > state.savedFrames },
        "${state.discardedFrames} removed after tracking trouble".takeIf { state.discardedFrames > 0 },
    ).joinToString(" — ")

    fun framesKept(count: Int): String = "${frames(count)} kept"

    /** Why frames are not being kept, or null while tracking is good. */
    fun tracking(status: MappingTrackingStatus?): String? = status?.issue?.let(::issue)

    fun trackingLevel(level: Level): String = when (level) {
        Level.GOOD -> "Tracking good"
        Level.WEAK -> "Tracking weak"
        Level.LOST -> "Tracking lost"
    }

    /** Stop is refused before the minimum, where nothing can build a map. */
    fun canFinish(state: MappingScanState): Boolean =
        state.phase == MappingPhase.Mapping && state.elapsedMillis >= MIN_MILLIS

    fun isInFinalMinute(state: MappingScanState): Boolean =
        state.phase == MappingPhase.Mapping && state.elapsedMillis >= FINAL_MINUTE_MILLIS

    /** The notice for a scan the SDK stopped by itself; the time limit is warned of a minute ahead instead. */
    fun stopped(reason: MappingAutoStopReason): String? = when (reason) {
        MappingAutoStopReason.INTERRUPTED -> "The scan was interrupted, so the scan stopped. What was captured has been kept."
        MappingAutoStopReason.TIME_LIMIT -> null
    }

    fun isValidName(raw: String): Boolean = raw.trim().length >= MappingLimits.MINIMUM_MAP_NAME_LENGTH

    /** Shown once something is typed and the trimmed name is still too short. */
    fun nameError(raw: String): String? =
        MappingError.NAME_TOO_SHORT.message.takeIf { raw.isNotBlank() && !isValidName(raw) }

    fun uploadPreparing(name: String) = "Preparing \"$name\"…"

    fun uploadUploading(name: String) = "Uploading \"$name\""

    fun uploadPercent(fraction: Double) = "${percent(fraction)}% — the upload continues while the app is running"

    fun uploadQueued(reason: String) =
        "$reason\n\nIt is safe to leave: the scan is in your drafts and uploads on its own once it can."

    fun uploadFailed(message: String) = "$message\n\nThe scan is saved in your drafts; you can retry from there."

    fun uploaded(name: String) =
        "\"$name\" was uploaded and is now processing. It will appear in your maps as Pending until the build finishes."

    private fun frames(count: Int) = "$count frame${if (count == 1) "" else "s"}"

    // Truncated, so 100% only once everything is done
    private fun percent(fraction: Double): Int = (fraction * PERCENT).toInt()

    private fun issue(issue: Issue): String = when (issue) {
        Issue.INITIALIZING -> "Getting started — move slowly"
        Issue.RELOCALIZING -> "Recovering tracking — return to where you were"
        Issue.EXCESSIVE_MOTION -> "Moving too fast — slow down"
        Issue.INSUFFICIENT_FEATURES -> "Not enough detail — aim at textured surfaces"
        Issue.UNAVAILABLE -> "Tracking unavailable"
        Issue.SETTLING -> "Hold steady — tracking is settling"
        Issue.LOW_DETAIL -> "Low detail — aim at furniture, edges or posters"
        Issue.TOO_DARK -> "Too dark — add light"
    }
}
