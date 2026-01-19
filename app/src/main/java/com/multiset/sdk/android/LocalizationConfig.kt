/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.sdk.android

/**
 * Localization Configuration for MultiSet SDK.
 *
 * modify these values to customize localization behavior.
 * These settings control how the AR activities perform localization.
 *
 * To customize, simply change the default values below before launching AR activities.
 */
object LocalizationConfig {
    // ============================================================
    // LOCALIZATION BEHAVIOR
    // ============================================================

    /**
     * Whether to automatically start localization when AR session begins.
     * If false, user must manually tap the capture/localize button.
     */
    var autoLocalize: Boolean = true

    /**
     * Whether to continue localization in background after first success.
     * Helps maintain accurate positioning over time.
     */
    var backgroundLocalization: Boolean = true

    /**
     * Interval between background localizations in seconds.
     * Only used when backgroundLocalization is true.
     * Valid range: 15 - 180 seconds.
     */
    var backgroundLocalizationIntervalSeconds: Float = 30f

    /**
     * Whether to enable relocalization when tracking is lost.
     * Automatically triggers localization when AR tracking state changes.
     */
    var relocalization: Boolean = true

    /**
     * Keep trying until first localization succeeds.
     * If true, failed localizations will silently retry until one succeeds.
     */
    var firstLocalizationUntilSuccess: Boolean = true

    // ============================================================
    // MULTI-FRAME CAPTURE SETTINGS
    // ============================================================

    /**
     * Number of frames to capture for multi-frame localization.
     * More frames = better accuracy but longer capture time.
     * Valid range: 4 - 6 frames.
     */
    var numberOfFrames: Int = 4

    /**
     * Interval between frame captures in milliseconds.
     * Allows user movement between frames for better coverage.
     * Valid range: 100 - 1000 ms.
     */
    var frameCaptureIntervalMs: Long = 500L

    // ============================================================
    // CONFIDENCE SETTINGS
    // ============================================================

    /**
     * Whether to check confidence threshold before accepting localization.
     * If true, localizations with confidence below threshold will be rejected.
     */
    var confidenceCheck: Boolean = false

    /**
     * Minimum confidence score to accept localization result.
     * Only used when confidenceCheck is true.
     * Valid range: 0.0 - 1.0.
     */
    var confidenceThreshold: Float = 0.3f

    // ============================================================
    // GPS SETTINGS
    // ============================================================

    /**
     * Whether to send GPS coordinates as a hint to improve localization.
     * Requires location permission. Useful for outdoor or large-scale maps.
     */
    var enableGeoHint: Boolean = false

    /**
     * Whether to include geo coordinates in localization response.
     * Useful if you need the world position of localized objects.
     */
    var includeGeoCoordinatesInResponse: Boolean = false

    // ============================================================
    // UI SETTINGS
    // ============================================================

    /**
     * Whether to show UI alerts (toasts) for localization status.
     * Shows success/failure messages to the user.
     */
    var showAlerts: Boolean = true

    /**
     * Whether to show 3D mesh overlay after successful localization.
     * The mesh helps visualize the mapped environment.
     */
    var enableMeshVisualization: Boolean = true

    // ============================================================
    // IMAGE QUALITY SETTINGS
    // ============================================================

    /**
     * JPEG quality for captured images sent to the localization API.
     * Higher quality = better accuracy but larger upload size.
     * Valid range: 50 - 100.
     */
    var imageQuality: Int = 90

    // ============================================================
    // HELPER METHODS
    // ============================================================

    /**
     * Reset all settings to default values.
     */
    fun resetToDefaults() {
        autoLocalize = true
        backgroundLocalization = true
        backgroundLocalizationIntervalSeconds = 30f
        relocalization = true
        firstLocalizationUntilSuccess = true
        numberOfFrames = 4
        frameCaptureIntervalMs = 500L
        confidenceCheck = false
        confidenceThreshold = 0.3f
        enableGeoHint = false
        includeGeoCoordinatesInResponse = false
        showAlerts = true
        enableMeshVisualization = true
        imageQuality = 90
    }

    /**
     * Apply validated bounds to all settings.
     * Call this after modifying settings to ensure valid values.
     */
    fun validate() {
        backgroundLocalizationIntervalSeconds = backgroundLocalizationIntervalSeconds.coerceIn(15f, 180f)
        numberOfFrames = numberOfFrames.coerceIn(4, 6)
        frameCaptureIntervalMs = frameCaptureIntervalMs.coerceIn(100L, 1000L)
        confidenceThreshold = confidenceThreshold.coerceIn(0f, 1f)
        imageQuality = imageQuality.coerceIn(50, 100)
    }
}
