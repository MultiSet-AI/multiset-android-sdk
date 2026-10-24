/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.pose

import android.content.Context
import androidx.xr.arcore.TrackingState
import androidx.xr.arcore.perceptionState
import androidx.xr.runtime.Config
import androidx.xr.runtime.CoreState
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import com.multiset.aura.platform.XrPermissions
import com.multiset.aura.platform.isXrPermissionMissing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Head pose at a CLOCK_MONOTONIC instant; [HeadPoseTracker] is the Jetpack XR implementation. */
interface HeadPoseSource {
    suspend fun poseAt(monotonicNanos: Long): PoseLookup
}

/**
 * Records Jetpack XR head poses with their CLOCK_MONOTONIC time, so a camera frame can be matched to
 * the pose at its exposure. Jetpack XR only exposes the latest pose, roughly every 20 ms.
 */
class HeadPoseTracker : HeadPoseSource {

    private val timeline = PoseTimeline(HISTORY_CAPACITY, MAX_SAMPLE_GAP_NS)
    private val newestSampleNanos = MutableStateFlow<Long?>(null)
    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.NotAvailable)
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    /** Records poses until cancelled. [session] must be the Activity's session (Compose XR's). */
    suspend fun track(session: Session, context: Context) {
        if (context.isXrPermissionMissing(XrPermissions.HEAD_TRACKING)) {
            _status.value = TrackingStatus.PermissionRequired
            return
        }
        _status.value = TrackingStatus.Starting
        try {
            val config = Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()
            val result = session.configure(config)
            if (result !is SessionConfigureSuccess) {
                _status.value = TrackingStatus.Failed("the session rejected device tracking ($result).")
                return
            }
            session.state.collect(::record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            _status.value = TrackingStatus.PermissionRequired
        } catch (e: Exception) {
            _status.value = TrackingStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The most recent head pose while tracking; null otherwise. */
    fun latestPose(): Pose? = if (_status.value == TrackingStatus.Tracking) timeline.newest()?.pose else null

    /** Waits briefly for a pose newer than [monotonicNanos], then interpolates to it. */
    override suspend fun poseAt(monotonicNanos: Long): PoseLookup {
        val status = _status.value
        if (status != TrackingStatus.Tracking) return PoseLookup.Unavailable(status.message)
        withTimeoutOrNull(SAMPLE_WAIT_MS) { newestSampleNanos.first { it != null && it >= monotonicNanos } }
        return timeline.poseAt(monotonicNanos)
    }

    /** The head pose now, for "where is the wearer" rather than frame timing; null while not tracking. */
    // 50 ms back, so the lookup interpolates inside the history instead of waiting for a newer sample.
    suspend fun currentPose(): Pose? = (poseAt(System.nanoTime() - 50_000_000L) as? PoseLookup.Found)?.pose

    private fun record(core: CoreState) {
        val device = core.perceptionState?.arDeviceState ?: return
        if (device.trackingState != TrackingState.TRACKING) {
            // Tracking may resume relocalized; poses on either side of the loss don't interpolate.
            timeline.clear()
            _status.value = TrackingStatus.NotTracking(stopped = device.trackingState == TrackingState.STOPPED)
            return
        }
        // The time mark is the XrTime the pose was located at, on CLOCK_MONOTONIC like nanoTime.
        val locatedAt = System.nanoTime() - core.timeMark.elapsedNow().inWholeNanoseconds
        timeline.add(TimedPose(locatedAt, device.devicePose))
        newestSampleNanos.value = timeline.newestNanos
        _status.value = TrackingStatus.Tracking
    }

    companion object {
        private const val HISTORY_CAPACITY = 256
        private const val MAX_SAMPLE_GAP_NS = 100_000_000L
        private const val SAMPLE_WAIT_MS = 200L
    }
}
