/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.multiset.sdk.model.AnchorPose
import com.multiset.sdk.model.Quat
import com.multiset.sdk.model.Vec3
import kotlin.math.acos
import kotlin.math.sin

// Core types only, so a JVM test can run it.
class AnchorSmoother(var durationSeconds: Double) {

    var target: AnchorPose? = null
        private set
    private var start: AnchorPose? = null
    private var startTime = 0.0

    // Starting from the shown pose keeps a target that lands mid-glide from jumping.
    fun setTarget(pose: AnchorPose, timeSeconds: Double, snap: Boolean) {
        start = if (snap) pose else (pose(timeSeconds) ?: pose)
        target = pose
        startTime = timeSeconds
    }

    fun pose(timeSeconds: Double): AnchorPose? {
        val from = start ?: return null
        val to = target ?: return null
        val progress = if (durationSeconds > 0) ((timeSeconds - startTime) / durationSeconds).coerceIn(0.0, 1.0).toFloat() else 1f
        return AnchorPose(lerp(from.position, to.position, progress), slerp(from.rotation, to.rotation, progress))
    }

    fun clear() {
        start = null
        target = null
    }

    private fun lerp(a: Vec3, b: Vec3, t: Float) = Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

    // Along the shorter arc, as simd_slerp.
    private fun slerp(a: Quat, b: Quat, t: Float): Quat {
        var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
        val end = if (dot < 0) Quat(-b.x, -b.y, -b.z, -b.w).also { dot = -dot } else b
        if (dot > 0.9995f) {
            return Quat(a.x + (end.x - a.x) * t, a.y + (end.y - a.y) * t, a.z + (end.z - a.z) * t, a.w + (end.w - a.w) * t).normalized()
        }
        val theta = acos(dot)
        val wa = sin((1 - t) * theta) / sin(theta)
        val wb = sin(t * theta) / sin(theta)
        return Quat(wa * a.x + wb * end.x, wa * a.y + wb * end.y, wa * a.z + wb * end.z, wa * a.w + wb * end.w)
    }
}
