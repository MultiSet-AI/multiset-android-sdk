/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.runstatus

import com.multiset.sdk.model.LocalizationStage

/** What the capture and server-wait overlays say, kept out of the activities so tests pin it. */
object RunStatusCopy {
    const val UPLOADING = "Uploading…"
    const val FINDING_OBJECT = "Finding the object…"
    const val MATCHING_MAP = "Matching against the map…"
    const val CAPTURING = "Capturing image…"
    const val CAPTURED = "Image captured"

    fun captureStatus(stage: LocalizationStage): String? {
        if (stage !is LocalizationStage.Capturing) return null
        if (stage.required <= 1) return if (stage.captured == 0) CAPTURING else CAPTURED
        return "Capturing image ${minOf(stage.captured + 1, stage.required)} of ${stage.required}"
    }

    fun serverWaitText(stage: LocalizationStage, objectTracking: Boolean): String = when {
        stage == LocalizationStage.Uploading -> UPLOADING
        objectTracking -> FINDING_OBJECT
        else -> MATCHING_MAP
    }
}
