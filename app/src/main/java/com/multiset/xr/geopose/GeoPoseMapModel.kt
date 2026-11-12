/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.geopose

import com.multiset.sdk.model.LocalizationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Owns what the map draws: the GeoPose of each fix and the device GPS track. Main thread only. */
class GeoPoseMapModel {

    private val _state = MutableStateFlow(GeoPoseMapState())
    val state: StateFlow<GeoPoseMapState> = _state.asStateFlow()

    private var gpsTrackStarted = false

    fun onGpsFix(fix: GpsFix) {
        _state.update { state ->
            state.copy(
                gpsTrack = if (gpsTrackStarted) state.gpsTrack + fix else state.gpsTrack,
                latestGps = fix,
            )
        }
    }

    /** The GPS track starts with the first localization, so both tracks cover the same walk. */
    fun onLocalizationRequested() {
        val starting = !gpsTrackStarted
        gpsTrackStarted = true
        _state.update { state ->
            state.copy(
                status = GeoPoseStatus.LOCALIZING,
                gpsTrack = if (starting) listOfNotNull(state.latestGps) else state.gpsTrack,
            )
        }
    }

    fun onLocalizationFailed() {
        _state.update { it.copy(status = GeoPoseStatus.FAILED) }
    }

    /** Returns the plotted point, or null when the response carried no place on Earth. */
    fun onLocalized(result: LocalizationResult, nowMs: Long): GeoPosePoint? {
        val point = geoPosePoint(result, nowMs)
        if (point == null) {
            _state.update { it.copy(status = GeoPoseStatus.NOT_GEOREFERENCED) }
            return null
        }
        _state.update { state ->
            state.copy(
                status = GeoPoseStatus.LOCALIZED,
                geoPoseTrack = state.geoPoseTrack + point,
                latestGeoPose = point,
            )
        }
        return point
    }

    fun setFollow(follow: Boolean) {
        if (_state.value.followMode != follow) _state.update { it.copy(followMode = follow) }
    }

    private fun geoPosePoint(result: LocalizationResult, nowMs: Long): GeoPosePoint? {
        val geoPose = result.geoPose ?: return null
        val position = geoPose.position ?: return null
        if (!GeoPoseMath.isOnEarth(position.latitude, position.longitude)) return null
        return GeoPosePoint(
            latitude = position.latitude,
            longitude = position.longitude,
            altitude = position.altitude,
            headingDegrees = geoPose.quaternion?.takeIf { GeoPoseMath.isHeadingFrame(geoPose.frameName) }?.let {
                GeoPoseMath.compassHeading(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.w.toDouble(), geoPose.frameName)
            },
            confidence = result.confidence,
            mapCodes = result.mapCodes.ifEmpty { listOf(result.mapCode) },
            timestampMs = nowMs,
        )
    }
}
