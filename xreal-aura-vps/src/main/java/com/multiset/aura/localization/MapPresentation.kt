/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import androidx.xr.runtime.math.Pose
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

private const val REANCHOR_DISTANCE_M = 0.02f
private const val REANCHOR_ANGLE_DEG = 0.5f

/**
 * Whether a new fix moved the map enough to re-anchor it. Background fixes land within a few
 * millimetres of each other; re-anchoring for those only costs a fresh anchor that is not yet placed.
 */
fun needsReanchor(current: Pose?, next: Pose): Boolean =
    current == null || distanceMetres(current, next) > REANCHOR_DISTANCE_M ||
        angleDegrees(current, next) > REANCHOR_ANGLE_DEG

fun distanceMetres(a: Pose, b: Pose): Float {
    val dx = b.translation.x - a.translation.x
    val dy = b.translation.y - a.translation.y
    val dz = b.translation.z - a.translation.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

fun angleDegrees(a: Pose, b: Pose): Float {
    val p = a.rotation
    val q = b.rotation
    val dot = abs(p.x * q.x + p.y * q.y + p.z * q.z + p.w * q.w).coerceAtMost(1f)
    return Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
}
