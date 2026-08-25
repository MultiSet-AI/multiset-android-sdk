/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.config

import com.multiset.sdk.model.QueryMode

/**
 * Localization Configuration for MultiSet SDK.
 *
 * SDK users can modify these values to customize localization behavior.
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
     * Enabled by default.
     */
    var confidenceCheck: Boolean = true

    /**
     * Minimum confidence score to accept localization result.
     * Only used when confidenceCheck is true.
     * Valid range: 0.2 - 0.8 (matches Unity SDK).
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
    // LOCALIZATION HINTS
    // ============================================================

    /**
     * Subset of map codes within a map set to restrict localization to.
     * Only used for map set localization; ignored for single-map localization.
     */
    var hintMapCodes: List<String> = emptyList()

    /**
     * Approximate position hint in "x,y,z" format to narrow the localization search.
     * Leave empty to disable.
     */
    var hintPosition: String = ""

    /**
     * Floor/ceiling height constraint in "floor,ceiling" format, e.g. "0,5".
     * Leave empty to disable.
     */
    var hintFloorHeight: String = ""

    /**
     * Search radius in meters for geo spatial filtering.
     * Applied when a geo hint or position hint is provided.
     * Valid range: 1 - 100.
     */
    var hintRadius: Int = 25

    /**
     * Whether to skip altitude (Y-axis) in geo hint spatial filtering,
     * using only horizontal distance (X and Z axes).
     */
    var use2DFiltering: Boolean = false

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
    // POSE CONSISTENCY (FALSE-POSITIVE CHECK)
    // ============================================================

    /**
     * Discard localization responses that contradict the device's own AR trajectory.
     * Defaults on here even though the SDK default is off, so testers get the check.
     */
    var poseConsistencyCheck: Boolean = true

    /**
     * How far a new fix may sit from the last accepted one before it is discarded, in metres.
     * Valid range: 3 - 30.
     */
    var poseConsistencyThreshold: Float = 10f

    // ============================================================
    // QUERY MODE
    // ============================================================

    /**
     * Search strategy for single-frame localization: VPS1 (standard) or VPS2 (deep search).
     * Multi-frame localization always runs VPS1 and ignores this setting.
     */
    var queryMode: QueryMode = QueryMode.VPS1

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
        confidenceCheck = true
        confidenceThreshold = 0.3f
        enableGeoHint = false
        includeGeoCoordinatesInResponse = false
        hintMapCodes = emptyList()
        hintPosition = ""
        hintFloorHeight = ""
        hintRadius = 25
        use2DFiltering = false
        showAlerts = true
        enableMeshVisualization = true
        imageQuality = 90
        queryMode = QueryMode.VPS1
        poseConsistencyCheck = true
        poseConsistencyThreshold = 10f
    }

    /**
     * Apply validated bounds to all settings.
     * Call this after modifying settings to ensure valid values.
     */
    fun validate() {
        backgroundLocalizationIntervalSeconds = backgroundLocalizationIntervalSeconds.coerceIn(15f, 180f)
        numberOfFrames = numberOfFrames.coerceIn(4, 6)
        frameCaptureIntervalMs = frameCaptureIntervalMs.coerceIn(100L, 1000L)
        confidenceThreshold = confidenceThreshold.coerceIn(0.2f, 0.8f)
        hintRadius = hintRadius.coerceIn(1, 100)
        imageQuality = imageQuality.coerceIn(50, 100)
        poseConsistencyThreshold = poseConsistencyThreshold.coerceIn(3f, 30f)
    }
}
