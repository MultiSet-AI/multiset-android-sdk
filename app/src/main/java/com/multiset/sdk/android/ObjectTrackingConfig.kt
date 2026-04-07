/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.sdk.android

/**
 * Object Tracking Configuration for MultiSet SDK.
 *
 * SDK users can modify these values to customize object tracking behavior.
 * These settings control how the Object Tracking activity performs tracking.
 *
 * To customize, simply change the default values below before launching the tracking activity.
 */
object ObjectTrackingConfig {
    // ============================================================
    // OBJECT CODES
    // ============================================================

    /**
     * List of object codes to track (max 10).
     * Set these before launching the ObjectTrackingActivity.
     */
    var objectCodes: Array<String> = emptyArray()

    // ============================================================
    // TRACKING BEHAVIOR
    // ============================================================

    /**
     * Whether to automatically start tracking when AR session begins.
     */
    var autoTracking: Boolean = true

    /**
     * Whether to continue tracking in background after first success.
     */
    var backgroundTracking: Boolean = true

    /**
     * Interval between background tracking attempts in seconds.
     * Valid range: 5 - 30 seconds.
     */
    var bgTrackingDurationSeconds: Float = 15f

    /**
     * Whether to restart tracking when AR tracking state is lost.
     */
    var restartTracking: Boolean = true

    /**
     * Keep trying until first tracking succeeds.
     * If true, failed tracking will silently retry until one succeeds.
     */
    var firstTrackingUntilSuccess: Boolean = true

    /**
     * Delay before capturing a frame in milliseconds.
     */
    var captureDelayMs: Long = 1000L

    // ============================================================
    // CONFIDENCE SETTINGS
    // ============================================================

    /**
     * Whether to check confidence threshold before accepting tracking.
     */
    var confidenceCheck: Boolean = true

    /**
     * Minimum confidence score to accept tracking result.
     * Valid range: 0.2 - 0.8.
     */
    var confidenceThreshold: Float = 0.3f

    // ============================================================
    // UI SETTINGS
    // ============================================================

    /**
     * Whether to show UI alerts (toasts) for tracking status.
     */
    var showAlerts: Boolean = true

    // ============================================================
    // IMAGE QUALITY SETTINGS
    // ============================================================

    /**
     * JPEG quality for captured images sent to the tracking API.
     * Valid range: 50 - 100.
     */
    var imageQuality: Int = 80

    // ============================================================
    // HELPER METHODS
    // ============================================================

    fun resetToDefaults() {
        objectCodes = emptyArray()
        autoTracking = true
        backgroundTracking = true
        bgTrackingDurationSeconds = 15f
        restartTracking = true
        firstTrackingUntilSuccess = true
        captureDelayMs = 1000L
        confidenceCheck = true
        confidenceThreshold = 0.3f
        showAlerts = true
        imageQuality = 80
    }

    fun validate() {
        bgTrackingDurationSeconds = bgTrackingDurationSeconds.coerceIn(5f, 30f)
        confidenceThreshold = confidenceThreshold.coerceIn(0.2f, 0.8f)
        imageQuality = imageQuality.coerceIn(50, 100)
        if (objectCodes.size > 10) {
            objectCodes = objectCodes.take(10).toTypedArray()
        }
    }
}
