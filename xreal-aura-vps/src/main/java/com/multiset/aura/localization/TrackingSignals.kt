/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import com.multiset.aura.pose.TrackingStatus

enum class TrackingSignal { INTERRUPTED, WORLD_ORIGIN_RESET, RESUMED }

/** What the MultiSet pose gate must be told when head tracking moves from [previous] to [current]. */
fun trackingSignal(previous: TrackingStatus, current: TrackingStatus): TrackingSignal? = when {
    previous == TrackingStatus.Tracking && current is TrackingStatus.NotTracking ->
        if (current.stopped) TrackingSignal.WORLD_ORIGIN_RESET else TrackingSignal.INTERRUPTED
    previous == TrackingStatus.Tracking && current is TrackingStatus.Failed -> TrackingSignal.WORLD_ORIGIN_RESET
    previous is TrackingStatus.NotTracking && !previous.stopped && current is TrackingStatus.NotTracking && current.stopped ->
        TrackingSignal.WORLD_ORIGIN_RESET
    previous is TrackingStatus.NotTracking && current == TrackingStatus.Tracking -> TrackingSignal.RESUMED
    else -> null
}
