/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.config

import android.content.Context
import com.multiset.sdk.model.QueryMode

/**
 * Persists the demo app's [LocalizationConfig] and [ObjectTrackingConfig] to
 * SharedPreferences so user adjustments survive app restarts.
 *
 * This lives in the app module (NOT the SDK) as a reference for native SDK
 * developers: the SDK exposes plain config objects, and it is up to the host
 * app to decide how/whether to persist them.
 *
 * Usage:
 *  - Call [load] once at app startup (MainActivity.onCreate) so saved values are
 *    applied before any AR activity reads the config objects.
 *  - Call [save] after the user edits values in the settings window.
 *
 * Note: credentials and map/object codes are intentionally NOT persisted here —
 * those come from multiset.properties via BuildConfig.
 */
object ConfigStore {
    private const val PREFS = "multiset_demo_config"

    // --- Localization keys ---
    private const val L_AUTO = "loc_autoLocalize"
    private const val L_BG = "loc_backgroundLocalization"
    private const val L_BG_INTERVAL = "loc_bgIntervalSeconds"
    private const val L_RELOCALIZE = "loc_relocalization"
    private const val L_FIRST_UNTIL = "loc_firstUntilSuccess"
    private const val L_FRAMES = "loc_numberOfFrames"
    private const val L_FRAME_INTERVAL = "loc_frameCaptureIntervalMs"
    private const val L_CONF_CHECK = "loc_confidenceCheck"
    private const val L_CONF_THRESHOLD = "loc_confidenceThreshold"
    private const val L_GEO_HINT = "loc_enableGeoHint"
    private const val L_GEO_RESPONSE = "loc_includeGeoInResponse"
    private const val L_HINT_MAP_CODES = "loc_hintMapCodes"
    private const val L_HINT_POSITION = "loc_hintPosition"
    private const val L_HINT_FLOOR = "loc_hintFloorHeight"
    private const val L_HINT_RADIUS = "loc_hintRadius"
    private const val L_USE_2D = "loc_use2DFiltering"
    private const val L_SHOW_ALERTS = "loc_showAlerts"
    private const val L_MESH = "loc_meshVisualization"
    private const val L_IMAGE_QUALITY = "loc_imageQuality"
    private const val L_QUERY_MODE = "loc_queryMode"
    private const val L_POSE_CHECK = "loc_poseConsistencyCheck"
    private const val L_POSE_THRESHOLD = "loc_poseConsistencyThreshold"

    // --- Object tracking keys ---
    private const val O_AUTO = "obj_autoTracking"
    private const val O_BG = "obj_backgroundTracking"
    private const val O_BG_DURATION = "obj_bgDurationSeconds"
    private const val O_RESTART = "obj_restartTracking"
    private const val O_FIRST_UNTIL = "obj_firstUntilSuccess"
    private const val O_CAPTURE_DELAY = "obj_captureDelayMs"
    private const val O_CONF_CHECK = "obj_confidenceCheck"
    private const val O_CONF_THRESHOLD = "obj_confidenceThreshold"
    private const val O_SHOW_ALERTS = "obj_showAlerts"
    private const val O_IMAGE_QUALITY = "obj_imageQuality"

    /** True once values have been persisted at least once. */
    fun hasSavedConfig(context: Context): Boolean =
        prefs(context).contains(L_AUTO)

    /**
     * Load saved values into [LocalizationConfig] and [ObjectTrackingConfig].
     * No-op (configs keep their defaults) if nothing has been saved yet.
     */
    fun load(context: Context) {
        val p = prefs(context)
        if (!p.contains(L_AUTO)) return

        with(LocalizationConfig) {
            autoLocalize = p.getBoolean(L_AUTO, autoLocalize)
            backgroundLocalization = p.getBoolean(L_BG, backgroundLocalization)
            backgroundLocalizationIntervalSeconds = p.getFloat(L_BG_INTERVAL, backgroundLocalizationIntervalSeconds)
            relocalization = p.getBoolean(L_RELOCALIZE, relocalization)
            firstLocalizationUntilSuccess = p.getBoolean(L_FIRST_UNTIL, firstLocalizationUntilSuccess)
            numberOfFrames = p.getInt(L_FRAMES, numberOfFrames)
            frameCaptureIntervalMs = p.getLong(L_FRAME_INTERVAL, frameCaptureIntervalMs)
            confidenceCheck = p.getBoolean(L_CONF_CHECK, confidenceCheck)
            confidenceThreshold = p.getFloat(L_CONF_THRESHOLD, confidenceThreshold)
            enableGeoHint = p.getBoolean(L_GEO_HINT, enableGeoHint)
            includeGeoCoordinatesInResponse = p.getBoolean(L_GEO_RESPONSE, includeGeoCoordinatesInResponse)
            hintMapCodes = decodeList(p.getString(L_HINT_MAP_CODES, "") ?: "")
            hintPosition = p.getString(L_HINT_POSITION, hintPosition) ?: hintPosition
            hintFloorHeight = p.getString(L_HINT_FLOOR, hintFloorHeight) ?: hintFloorHeight
            hintRadius = p.getInt(L_HINT_RADIUS, hintRadius)
            use2DFiltering = p.getBoolean(L_USE_2D, use2DFiltering)
            showAlerts = p.getBoolean(L_SHOW_ALERTS, showAlerts)
            enableMeshVisualization = p.getBoolean(L_MESH, enableMeshVisualization)
            imageQuality = p.getInt(L_IMAGE_QUALITY, imageQuality)
            queryMode = decodeQueryMode(p.getString(L_QUERY_MODE, null), queryMode)
            poseConsistencyCheck = p.getBoolean(L_POSE_CHECK, poseConsistencyCheck)
            poseConsistencyThreshold = p.getFloat(L_POSE_THRESHOLD, poseConsistencyThreshold)
            validate()
        }

        with(ObjectTrackingConfig) {
            autoTracking = p.getBoolean(O_AUTO, autoTracking)
            backgroundTracking = p.getBoolean(O_BG, backgroundTracking)
            bgTrackingDurationSeconds = p.getFloat(O_BG_DURATION, bgTrackingDurationSeconds)
            restartTracking = p.getBoolean(O_RESTART, restartTracking)
            firstTrackingUntilSuccess = p.getBoolean(O_FIRST_UNTIL, firstTrackingUntilSuccess)
            captureDelayMs = p.getLong(O_CAPTURE_DELAY, captureDelayMs)
            confidenceCheck = p.getBoolean(O_CONF_CHECK, confidenceCheck)
            confidenceThreshold = p.getFloat(O_CONF_THRESHOLD, confidenceThreshold)
            showAlerts = p.getBoolean(O_SHOW_ALERTS, showAlerts)
            imageQuality = p.getInt(O_IMAGE_QUALITY, imageQuality)
            validate()
        }
    }

    /** Persist the current values of both config objects. */
    fun save(context: Context) {
        prefs(context).edit().apply {
            with(LocalizationConfig) {
                putBoolean(L_AUTO, autoLocalize)
                putBoolean(L_BG, backgroundLocalization)
                putFloat(L_BG_INTERVAL, backgroundLocalizationIntervalSeconds)
                putBoolean(L_RELOCALIZE, relocalization)
                putBoolean(L_FIRST_UNTIL, firstLocalizationUntilSuccess)
                putInt(L_FRAMES, numberOfFrames)
                putLong(L_FRAME_INTERVAL, frameCaptureIntervalMs)
                putBoolean(L_CONF_CHECK, confidenceCheck)
                putFloat(L_CONF_THRESHOLD, confidenceThreshold)
                putBoolean(L_GEO_HINT, enableGeoHint)
                putBoolean(L_GEO_RESPONSE, includeGeoCoordinatesInResponse)
                putString(L_HINT_MAP_CODES, encodeList(hintMapCodes))
                putString(L_HINT_POSITION, hintPosition)
                putString(L_HINT_FLOOR, hintFloorHeight)
                putInt(L_HINT_RADIUS, hintRadius)
                putBoolean(L_USE_2D, use2DFiltering)
                putBoolean(L_SHOW_ALERTS, showAlerts)
                putBoolean(L_MESH, enableMeshVisualization)
                putInt(L_IMAGE_QUALITY, imageQuality)
                putString(L_QUERY_MODE, queryMode.name)
                putBoolean(L_POSE_CHECK, poseConsistencyCheck)
                putFloat(L_POSE_THRESHOLD, poseConsistencyThreshold)
            }
            with(ObjectTrackingConfig) {
                putBoolean(O_AUTO, autoTracking)
                putBoolean(O_BG, backgroundTracking)
                putFloat(O_BG_DURATION, bgTrackingDurationSeconds)
                putBoolean(O_RESTART, restartTracking)
                putBoolean(O_FIRST_UNTIL, firstTrackingUntilSuccess)
                putLong(O_CAPTURE_DELAY, captureDelayMs)
                putBoolean(O_CONF_CHECK, confidenceCheck)
                putFloat(O_CONF_THRESHOLD, confidenceThreshold)
                putBoolean(O_SHOW_ALERTS, showAlerts)
                putInt(O_IMAGE_QUALITY, imageQuality)
            }
        }.apply()
    }

    /** Reset both config objects to defaults and persist the cleared state. */
    // A stored name can outlive the enum constant it refers to; fall back rather than crash.
    private fun decodeQueryMode(stored: String?, fallback: QueryMode): QueryMode =
        stored?.let { runCatching { QueryMode.valueOf(it) }.getOrNull() } ?: fallback

    fun resetToDefaults(context: Context) {
        // Preserve runtime objectCodes (sourced from BuildConfig, not the settings UI).
        val objectCodes = ObjectTrackingConfig.objectCodes
        LocalizationConfig.resetToDefaults()
        ObjectTrackingConfig.resetToDefaults()
        ObjectTrackingConfig.objectCodes = objectCodes
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encodeList(values: List<String>): String =
        values.joinToString(",")

    private fun decodeList(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
