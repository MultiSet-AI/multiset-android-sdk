/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.pose

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3

/** A head pose and the CLOCK_MONOTONIC time ([System.nanoTime]) at which it was located. */
data class TimedPose(val monotonicNanos: Long, val pose: Pose)

sealed interface PoseLookup {
    /** [pose] is interpolated between [before] and [after]; they are the same sample on an exact hit. */
    data class Found(val pose: Pose, val before: TimedPose, val after: TimedPose) : PoseLookup
    data class Unavailable(val reason: String) : PoseLookup
}

/** Recent head poses in time order, for looking up the pose at a camera exposure. Thread-safe. */
class PoseTimeline(private val capacity: Int, private val maxGapNanos: Long) {

    private val samples = ArrayDeque<TimedPose>(capacity)

    val newestNanos: Long? get() = synchronized(samples) { samples.lastOrNull()?.monotonicNanos }

    fun add(sample: TimedPose): Unit = synchronized(samples) {
        val newest = samples.lastOrNull()
        if (newest != null && sample.monotonicNanos <= newest.monotonicNanos) return
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(sample)
    }

    fun clear(): Unit = synchronized(samples) { samples.clear() }

    fun newest(): TimedPose? = synchronized(samples) { samples.lastOrNull() }

    fun poseAt(monotonicNanos: Long): PoseLookup = synchronized(samples) {
        if (samples.isEmpty()) return PoseLookup.Unavailable("No head-pose samples were recorded.")
        val afterIndex = samples.indexOfFirst { it.monotonicNanos >= monotonicNanos }
        if (afterIndex == -1) {
            val lag = monotonicNanos - samples.last().monotonicNanos
            return PoseLookup.Unavailable("No head-pose sample after the exposure; the newest is ${lag.asMs()} older.")
        }
        val after = samples[afterIndex]
        if (after.monotonicNanos == monotonicNanos) return PoseLookup.Found(after.pose, after, after)
        if (afterIndex == 0) {
            val lead = after.monotonicNanos - monotonicNanos
            return PoseLookup.Unavailable("The exposure is ${lead.asMs()} older than the head-pose history.")
        }
        val before = samples[afterIndex - 1]
        val gap = after.monotonicNanos - before.monotonicNanos
        if (gap > maxGapNanos) {
            return PoseLookup.Unavailable(
                "Head-pose samples around the exposure are ${gap.asMs()} apart; tracking was interrupted."
            )
        }
        val fraction = ((monotonicNanos - before.monotonicNanos).toDouble() / gap).toFloat()
        PoseLookup.Found(interpolate(before.pose, after.pose, fraction), before, after)
    }

    private fun interpolate(from: Pose, to: Pose, fraction: Float) = Pose(
        Vector3.lerp(from.translation, to.translation, fraction),
        Quaternion.slerp(from.rotation, to.rotation, fraction),
    )

    private fun Long.asMs() = "%.1f ms".format(this / 1e6)
}
