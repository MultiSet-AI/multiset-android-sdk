/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.frame

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3

/** Upright camera (sensor frame turned by `SENSOR_ORIENTATION`) in the head's frame. */
data class HeadToCamera(val pose: Pose, val isCalibrated: Boolean) {
    companion object {
        val UNCALIBRATED = HeadToCamera(Pose(), isCalibrated = false)
    }
}

/**
 * Poses follow OpenXR: right-handed, metres, +X right, +Y up, looking along -Z. A camera's +X and +Y
 * follow its image's columns and rows (the image's +Y is up, against the pixel row order).
 */
object CameraPoseCalculator {

    private val OPTICAL_AXIS = Vector3(0f, 0f, 1f)

    /**
     * Pose of the camera that would have produced the saved image directly.
     *
     * [headToUprightCamera] places the camera, turned upright by `SENSOR_ORIENTATION`, in the head's
     * frame. A clockwise turn of the image's pixels is a counter-clockwise roll of the camera.
     */
    fun savedImageCameraPose(
        devicePose: Pose,
        headToUprightCamera: Pose,
        sensorOrientationDegrees: Int,
        imageRotationCwDegrees: Int,
    ): Pose {
        val roll = Quaternion.fromAxisAngle(OPTICAL_AXIS, (imageRotationCwDegrees - sensorOrientationDegrees).toFloat())
        return devicePose.compose(headToUprightCamera).compose(Pose(Vector3(), roll))
    }
}
