/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.pose

/** Clock behind `SENSOR_TIMESTAMP`, from `SENSOR_INFO_TIMESTAMP_SOURCE`. */
enum class SensorClock(val description: String) {
    BOOTTIME("REALTIME source (elapsedRealtimeNanos), converted to CLOCK_MONOTONIC"),
    MONOTONIC_ASSUMED("UNKNOWN source, assumed to be CLOCK_MONOTONIC"),
}

/** Camera2 timing of one frame; `SENSOR_TIMESTAMP` marks the start of exposure of the first row. */
data class ExposureTiming(
    val sensorTimestampNanos: Long,
    val exposureTimeNanos: Long?,
    val rollingShutterSkewNanos: Long?,
    val clock: SensorClock,
) {
    /** Middle of the middle row's exposure, on CLOCK_MONOTONIC ([System.nanoTime]). */
    fun midpointMonotonicNanos(bootMinusMonotonicNanos: Long): Long {
        val start = when (clock) {
            SensorClock.BOOTTIME -> sensorTimestampNanos - bootMinusMonotonicNanos
            SensorClock.MONOTONIC_ASSUMED -> sensorTimestampNanos
        }
        return start + (rollingShutterSkewNanos ?: 0) / 2 + (exposureTimeNanos ?: 0) / 2
    }
}
