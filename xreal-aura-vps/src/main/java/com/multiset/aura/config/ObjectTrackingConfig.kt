/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.config

data class ObjectTrackingConfig(
    val autoTracking: Boolean = true,
    val backgroundTracking: Boolean = true,
    val backgroundIntervalSeconds: Int = 15,
    val restartTracking: Boolean = true,
    val firstTrackingUntilSuccess: Boolean = true,
    val captureDelayMs: Long = 1000,
    val confidenceCheck: Boolean = true,
    val confidenceThreshold: Float = 0.3f,
    val imageQuality: Int = 80,
    val showAlerts: Boolean = true,
    val showOutline: Boolean = true,
) {
    fun validated(): ObjectTrackingConfig = copy(
        backgroundIntervalSeconds = backgroundIntervalSeconds.coerceIn(5, 30),
        captureDelayMs = captureDelayMs.coerceIn(0L, 5000L),
        confidenceThreshold = confidenceThreshold.coerceIn(0.2f, 0.8f),
        imageQuality = imageQuality.coerceIn(50, 100),
    )
}
