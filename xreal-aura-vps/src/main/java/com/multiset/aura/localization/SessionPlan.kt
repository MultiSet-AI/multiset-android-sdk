/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import com.multiset.aura.config.LocalizationConfig
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.model.QueryMode

/** Every value a `LocalizationSession` is built with. */
data class SessionPlan(
    val mode: LocalizationMode,
    val queryMode: QueryMode,
    val backgroundLocalization: Boolean,
    val backgroundIntervalMs: Long,
    val numberOfFrames: Int,
    val frameCaptureIntervalMs: Long,
    val confidenceCheck: Boolean,
    val confidenceThreshold: Float,
    val firstLocalizationUntilSuccess: Boolean,
    val poseConsistencyCheck: Boolean,
    val poseConsistencyThreshold: Float,
    val poseConsistencyYawThreshold: Float,
    val hintMapCodes: List<String>,
    val imageQuality: Int,
    val autoLocalize: Boolean,
    val relocalization: Boolean,
) {
    /** [autoLocalize] and [relocalization] only steer the controller, never the session. */
    fun needsNewSession(previous: SessionPlan): Boolean =
        copy(autoLocalize = previous.autoLocalize, relocalization = previous.relocalization) != previous

    companion object {
        fun of(config: LocalizationConfig): SessionPlan = SessionPlan(
            mode = config.mode,
            queryMode = config.queryMode,
            backgroundLocalization = config.backgroundLocalization,
            backgroundIntervalMs = config.backgroundIntervalSeconds * 1000L,
            numberOfFrames = config.numberOfFrames,
            frameCaptureIntervalMs = config.frameCaptureIntervalMs,
            confidenceCheck = config.confidenceCheck,
            confidenceThreshold = config.confidenceThreshold,
            firstLocalizationUntilSuccess = config.firstLocalizationUntilSuccess,
            poseConsistencyCheck = config.poseConsistencyCheck,
            poseConsistencyThreshold = config.poseConsistencyThreshold,
            poseConsistencyYawThreshold = config.poseConsistencyYawThreshold,
            hintMapCodes = config.hintMapCodes,
            imageQuality = config.imageQuality,
            autoLocalize = config.autoLocalize,
            relocalization = config.relocalization,
        )
    }
}
