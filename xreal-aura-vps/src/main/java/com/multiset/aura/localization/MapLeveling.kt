/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A MultiSet map is level (Y-up) and so is the world it is placed in (head tracking and ARCore are
 * gravity-aligned), so any tilt in a fix is error. It comes from the server's estimate of the camera's
 * rotation, which turns the whole map about the camera. On Aura, with the nominal focal length, even
 * good fixes tilted 1–1.6°, and fixes that jumped the map by 40 cm tilted it 3.4–5.2°.
 */
object MapLeveling {

    /** Past this, a fix is too wrong to move an already placed map; calibrated fixes tilt up to 2.6°. */
    const val MAX_TILT_DEGREES = 4f

    /**
     * Whether a fix tilting [tilt] degrees is ignored, given the tilt of the fix that placed the map
     * (null before the first). A badly tilted first fix must not lock out better ones.
     */
    fun rejects(tilt: Float, placedTilt: Float?): Boolean =
        placedTilt != null && tilt > MAX_TILT_DEGREES && tilt > placedTilt

    /** The angle between the map's up and the world's up. */
    fun tiltDegrees(rotation: Quaternion): Float {
        val up = rotation * Vector3(0f, 1f, 0f)
        return Math.toDegrees(acos(up.y.coerceIn(-1f, 1f) / up.length).toDouble()).toFloat()
    }

    /** [mapPose] turned level about [camera] (world space), so the camera keeps its place in the map. */
    fun level(mapPose: Pose, camera: Vector3): Pose {
        val q = mapPose.rotation
        val norm = sqrt(q.y * q.y + q.w * q.w)
        if (norm < 1e-6f) return mapPose
        val heading = Quaternion(0f, q.y / norm, 0f, q.w / norm)
        val inMap = mapPose.inverse.transformPoint(camera)
        return Pose(camera - heading * inMap, heading)
    }
}
