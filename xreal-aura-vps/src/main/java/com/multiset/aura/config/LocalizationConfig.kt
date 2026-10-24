/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.config

import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.model.QueryMode

data class LocalizationConfig(
    val mode: LocalizationMode = MultiSetSDKConfig.DEFAULT_LOCALIZATION_MODE,
    val queryMode: QueryMode = QueryMode.VPS1,
    val autoLocalize: Boolean = true,
    val backgroundLocalization: Boolean = true,
    val backgroundIntervalSeconds: Int = 30,
    val relocalization: Boolean = true,
    val firstLocalizationUntilSuccess: Boolean = true,
    val numberOfFrames: Int = 4,
    val frameCaptureIntervalMs: Long = 500,
    val confidenceCheck: Boolean = true,
    val confidenceThreshold: Float = 0.3f,
    val poseConsistencyCheck: Boolean = MultiSetSDKConfig.DEFAULT_POSE_CONSISTENCY_CHECK,
    val poseConsistencyThreshold: Float = 4f,
    val poseConsistencyYawThreshold: Float = 25f,
    val hintMapCodes: List<String> = emptyList(),
    val imageQuality: Int = 90,
    val showAlerts: Boolean = true,
    val showMesh: Boolean = true,
    val showGizmo: Boolean = true,
    val cameraPreview: Boolean = true,
) {
    fun validated(): LocalizationConfig = copy(
        backgroundIntervalSeconds = backgroundIntervalSeconds.coerceIn(10, 180),
        numberOfFrames = numberOfFrames.coerceIn(4, 6),
        frameCaptureIntervalMs = frameCaptureIntervalMs.coerceIn(100L, 1000L),
        confidenceThreshold = confidenceThreshold.coerceIn(0.2f, 0.8f),
        poseConsistencyThreshold = poseConsistencyThreshold.coerceIn(1.5f, 15f),
        poseConsistencyYawThreshold = poseConsistencyYawThreshold.coerceIn(10f, 60f),
        imageQuality = imageQuality.coerceIn(50, 100),
    )
}
