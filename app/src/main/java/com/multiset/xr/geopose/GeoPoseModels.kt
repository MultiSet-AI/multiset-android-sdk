/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.geopose

/** One device GPS fix, the blue track. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Double?,
    val fixTimestampMs: Long,
)

/** One localization's GeoPose, the green track. [headingDegrees] is clockwise from true north. */
data class GeoPosePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val headingDegrees: Double?,
    val confidence: Float?,
    val mapCodes: List<String>,
    val timestampMs: Long,
)

enum class GeoPoseStatus { IDLE, LOCALIZING, LOCALIZED, NOT_GEOREFERENCED, FAILED }

/** What the map draws. */
data class GeoPoseMapState(
    val gpsTrack: List<GpsFix> = emptyList(),
    val geoPoseTrack: List<GeoPosePoint> = emptyList(),
    val latestGps: GpsFix? = null,
    val latestGeoPose: GeoPosePoint? = null,
    val status: GeoPoseStatus = GeoPoseStatus.IDLE,
    val followMode: Boolean = true,
)
