/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.multiset.sdk.session.LocalizationSession
import com.multiset.sdk.session.ObjectTrackingSession
import com.multiset.sdk.model.TrackingState as SdkTrackingState

/**
 * What an ARCore tracking change means to a session, as the iOS adapter maps ARKit's.
 *
 * `BAD_STATE` and `STOPPED` mean the world origin is no longer meaningful. A lost camera is an
 * interruption. Excessive motion, poor features or light and initializing are a limited spell: only
 * reported as paused, so the session leaves the scene in place unless it lasts 2 s.
 */
enum class ArTrackingBreak { NONE, INTERRUPTED, WORLD_RESET }

fun arTrackingBreak(state: TrackingState, reason: TrackingFailureReason): ArTrackingBreak = when {
    state == TrackingState.TRACKING -> ArTrackingBreak.NONE
    state == TrackingState.STOPPED || reason == TrackingFailureReason.BAD_STATE -> ArTrackingBreak.WORLD_RESET
    reason == TrackingFailureReason.CAMERA_UNAVAILABLE -> ArTrackingBreak.INTERRUPTED
    else -> ArTrackingBreak.NONE
}

fun TrackingState.toSdk(): SdkTrackingState = when (this) {
    TrackingState.TRACKING -> SdkTrackingState.TRACKING
    TrackingState.PAUSED -> SdkTrackingState.PAUSED
    TrackingState.STOPPED -> SdkTrackingState.STOPPED
}

/**
 * Report every ARCore tracking change, the first state and the return to [TrackingState.TRACKING]
 * included. The state goes first: a session recovers only for a host that reports tracking.
 */
fun LocalizationSession.reportArTracking(state: TrackingState, reason: TrackingFailureReason) {
    reportTrackingState(state.toSdk())
    when (arTrackingBreak(state, reason)) {
        ArTrackingBreak.INTERRUPTED -> notifyTrackingInterrupted()
        ArTrackingBreak.WORLD_RESET -> notifyWorldOriginReset()
        ArTrackingBreak.NONE -> Unit
    }
}

/** Report every ARCore tracking change, the first state and the return to [TrackingState.TRACKING] included. */
fun ObjectTrackingSession.reportArTracking(state: TrackingState, reason: TrackingFailureReason) {
    reportTrackingState(state.toSdk())
    when (arTrackingBreak(state, reason)) {
        ArTrackingBreak.INTERRUPTED -> notifyTrackingInterrupted()
        ArTrackingBreak.WORLD_RESET -> notifyWorldOriginReset()
        ArTrackingBreak.NONE -> Unit
    }
}
