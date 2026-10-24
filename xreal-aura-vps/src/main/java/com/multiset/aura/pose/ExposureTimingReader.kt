/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.pose

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult

/** Null when the camera reports no sensor timestamp for the frame. */
fun readExposureTiming(result: CaptureResult, ch: CameraCharacteristics): ExposureTiming? {
    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return null
    val realtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    return ExposureTiming(
        sensorTimestampNanos = timestamp,
        exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        rollingShutterSkewNanos = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
        clock = if (realtime) SensorClock.BOOTTIME else SensorClock.MONOTONIC_ASSUMED,
    )
}
