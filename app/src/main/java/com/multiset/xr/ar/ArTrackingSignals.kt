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

/**
 * Maps ARCore's tracking-loss signals onto the pose consistency gate.
 *
 * `BAD_STATE` and `STOPPED` mean the world origin is no longer meaningful, so every offset measured
 * in it is discarded. Excessive motion, poor features and poor light are recoverable and only mark
 * the anchor stale, so a tracker that recovers in the same place still validates against it.
 */
fun LocalizationSession.notifyArTrackingLost(state: TrackingState, reason: TrackingFailureReason) {
    if (state == TrackingState.STOPPED || reason == TrackingFailureReason.BAD_STATE) {
        notifyWorldOriginReset()
    } else {
        notifyTrackingInterrupted()
    }
}
