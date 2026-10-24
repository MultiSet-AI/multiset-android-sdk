/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.mapping

import com.multiset.sdk.mapping.MappingDraft
import com.multiset.sdk.mapping.MappingUploadState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the draft maps screen says about each draft, kept out of the activity so tests can pin it. */
object DraftMapsCopy {
    private const val PERCENT = 100
    private const val DECIMAL_UNIT = 1000.0

    const val TITLE = "Draft maps"
    const val EMPTY_TITLE = "No drafts on this device"
    const val EMPTY_MESSAGE = "A scan you save without uploading waits here until you send it to your account."
    const val PACKAGE_MISSING = "Package missing"
    const val DELETE_TITLE = "Delete this scan?"
    const val DELETE_MESSAGE = "The capture is only on this device. Deleting it cannot be undone."
    const val KEEP = "Keep"

    /** A button a draft row offers. */
    enum class Action(val label: String) {
        UPLOAD("Upload"),
        RETRY_NOW("Retry now"),
        TRY_AGAIN("Try again"),
        DELETE("Delete"),
    }

    /** The colour family of a row's status pill. */
    enum class Tone { NEUTRAL, POSITIVE, CAUTION, NEGATIVE }

    fun header(count: Int) = "$count scan${if (count == 1) "" else "s"} on this device, waiting to be uploaded"

    fun badge(count: Int) = "$count draft${if (count == 1) "" else "s"}"

    fun pill(state: MappingUploadState?): String = when (state) {
        MappingUploadState.Preparing -> "Preparing"
        is MappingUploadState.Uploading -> "Uploading ${percent(state.fraction)}%"
        is MappingUploadState.Waiting -> "Queued"
        is MappingUploadState.Failed -> "Upload failed"
        null -> "Ready to upload"
    }

    fun tone(state: MappingUploadState?): Tone = when (state) {
        MappingUploadState.Preparing -> Tone.NEUTRAL
        is MappingUploadState.Uploading -> Tone.POSITIVE
        is MappingUploadState.Waiting, null -> Tone.CAUTION
        is MappingUploadState.Failed -> Tone.NEGATIVE
    }

    /** The line under the row's details: progress, the waiting reason or the failure. */
    fun note(state: MappingUploadState?): String? = when (state) {
        MappingUploadState.Preparing -> "Preparing the upload…"
        is MappingUploadState.Uploading -> "Uploading ${percent(state.fraction)}%. You can leave this screen."
        is MappingUploadState.Waiting -> state.reason
        is MappingUploadState.Failed -> state.message
        null -> null
    }

    /** Nothing while a transfer is under way; otherwise the upload that fits the state, then Delete. */
    fun actions(state: MappingUploadState?): List<Action> = when (state) {
        MappingUploadState.Preparing, is MappingUploadState.Uploading -> emptyList()
        is MappingUploadState.Waiting -> listOf(Action.RETRY_NOW, Action.DELETE)
        is MappingUploadState.Failed -> listOf(Action.TRY_AGAIN, Action.DELETE)
        null -> listOf(Action.UPLOAD, Action.DELETE)
    }

    /** "142 frames · 11.0 MB", or what remains when a part is unknown; null reads as [PACKAGE_MISSING]. */
    fun facts(draft: MappingDraft): String? {
        val parts = buildList {
            val frames = draft.frameCount ?: 0
            if (frames > 0) add("$frames frame${if (frames == 1) "" else "s"}")
            draft.packageBytes?.let { add(formatBytes(it)) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** "Scanned Sep 7, 11:58 PM"; the year only when it is not this one. */
    fun scanned(packagedAtMillis: Long?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = Instant.ofEpochMilli(packagedAtMillis ?: return null).atZone(zone)
        val pattern = if (at.year == now.atZone(zone).year) "MMM d, h:mm a" else "MMM d, yyyy, h:mm a"
        return "Scanned ${DateTimeFormatter.ofPattern(pattern, Locale.US).format(at)}"
    }

    /** Decimal units with one decimal, as the file size of a package. */
    fun formatBytes(bytes: Long): String {
        if (bytes < DECIMAL_UNIT) return "$bytes B"
        var value = bytes / DECIMAL_UNIT
        val units = listOf("KB", "MB", "GB", "TB")
        for (unit in units) {
            if (value < DECIMAL_UNIT || unit == units.last()) return String.format(Locale.US, "%.1f %s", value, unit)
            value /= DECIMAL_UNIT
        }
        return "$bytes B"
    }

    // Truncated, so 100% only once everything is sent
    private fun percent(fraction: Double): Int = (fraction * PERCENT).toInt()
}
