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
import com.multiset.sdk.model.AnchorPose
import com.multiset.sdk.model.Quat
import com.multiset.sdk.model.Vec3

// Jetpack XR and the MultiSet SDK share one convention (right-handed, +Y up), so these copy fields.

fun Pose.toSdkPosition(): Vec3 = Vec3(translation.x, translation.y, translation.z)

fun Pose.toSdkRotation(): Quat = Quat(rotation.x, rotation.y, rotation.z, rotation.w)

/** [position] is `[x, y, z]` and [rotation] is `[x, y, z, w]`, as in `LocalizationResult`. */
fun sdkPose(position: FloatArray, rotation: FloatArray): Pose =
    Pose(Vector3(position[0], position[1], position[2]), Quaternion(rotation[0], rotation[1], rotation[2], rotation[3]))

fun AnchorPose.toPose(): Pose =
    Pose(Vector3(position.x, position.y, position.z), Quaternion(rotation.x, rotation.y, rotation.z, rotation.w))
