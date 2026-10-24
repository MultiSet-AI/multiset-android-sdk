/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.multiset.sdk.mapping.MappingTrackingState

/** Reads ARCore's tracking state as the SDK's [MappingTrackingState]. */
object MappingTrackingReadings {
    /** ARCore has no "initializing" or "relocalizing": a pause with no reason is one or the other by [hasTracked]. */
    fun of(state: TrackingState, reason: TrackingFailureReason, hasTracked: Boolean): MappingTrackingState =
        when (state) {
            TrackingState.TRACKING -> MappingTrackingState.NORMAL
            TrackingState.STOPPED -> MappingTrackingState.UNAVAILABLE
            TrackingState.PAUSED -> when (reason) {
                TrackingFailureReason.NONE ->
                    if (hasTracked) MappingTrackingState.RELOCALIZING else MappingTrackingState.INITIALIZING
                TrackingFailureReason.EXCESSIVE_MOTION -> MappingTrackingState.EXCESSIVE_MOTION
                TrackingFailureReason.INSUFFICIENT_FEATURES,
                TrackingFailureReason.INSUFFICIENT_LIGHT,
                -> MappingTrackingState.INSUFFICIENT_FEATURES
                TrackingFailureReason.BAD_STATE,
                TrackingFailureReason.CAMERA_UNAVAILABLE,
                -> MappingTrackingState.UNAVAILABLE
            }
        }
}
