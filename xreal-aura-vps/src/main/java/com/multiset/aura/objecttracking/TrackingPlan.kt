/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.objecttracking

import com.multiset.aura.config.ObjectTrackingConfig

/** Every value an `ObjectTrackingSession` is given; [autoTracking] and [restartTracking] steer the controller. */
data class TrackingPlan(
    val backgroundTracking: Boolean,
    val backgroundIntervalMs: Long,
    val firstTrackingUntilSuccess: Boolean,
    val captureDelayMs: Long,
    val confidenceCheck: Boolean,
    val confidenceThreshold: Float,
    val imageQuality: Int,
    val autoTracking: Boolean,
    val restartTracking: Boolean,
) {
    companion object {
        fun of(config: ObjectTrackingConfig): TrackingPlan = TrackingPlan(
            backgroundTracking = config.backgroundTracking,
            backgroundIntervalMs = config.backgroundIntervalSeconds * 1000L,
            firstTrackingUntilSuccess = config.firstTrackingUntilSuccess,
            captureDelayMs = config.captureDelayMs,
            confidenceCheck = config.confidenceCheck,
            confidenceThreshold = config.confidenceThreshold,
            imageQuality = config.imageQuality,
            autoTracking = config.autoTracking,
            restartTracking = config.restartTracking,
        )
    }
}
