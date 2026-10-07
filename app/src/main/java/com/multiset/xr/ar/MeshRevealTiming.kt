/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.multiset.sdk.model.Vec3
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Timing for the map mesh's radial reveal, ported from iOS `MeshRevealAnimator.swift`.
 *
 * The sweep's duration follows a power curve through (10 m, 6 s) and (200 m, 22 s), so a room and
 * a warehouse both get a sensible sweep; after each sweep the full mesh holds for
 * [DELAY_BETWEEN_LOOPS] seconds before the next one starts from the live camera.
 */
object MeshRevealTiming {
    const val SMALL_RADIUS = 10f
    const val SMALL_RADIUS_DURATION = 6f
    const val LARGE_RADIUS = 200f
    const val LARGE_RADIUS_DURATION = 22f

    /** Covers float error only: the furthest bounding-box corner already lies beyond the furthest vertex. */
    const val RADIUS_PADDING = 0.5f

    const val DELAY_BETWEEN_LOOPS = 20f

    /** Seconds for a sweep to reach [radius] metres; radii under 1 m are treated as 1 m. */
    fun duration(radius: Float): Float {
        val r = max(radius, 1f)
        val exponent = ln(LARGE_RADIUS_DURATION / SMALL_RADIUS_DURATION) / ln(LARGE_RADIUS / SMALL_RADIUS)
        val coefficient = SMALL_RADIUS_DURATION / SMALL_RADIUS.pow(exponent)
        return coefficient * r.pow(exponent)
    }

    /** Distance from [centre] to the furthest corner of the world bounds, plus padding; [SMALL_RADIUS] without bounds. */
    fun maxRadius(centre: Vec3, boundsMin: Vec3?, boundsMax: Vec3?): Float {
        if (boundsMin == null || boundsMax == null) return SMALL_RADIUS
        var furthest = 0f
        for (x in floatArrayOf(boundsMin.x, boundsMax.x)) {
            for (y in floatArrayOf(boundsMin.y, boundsMax.y)) {
                for (z in floatArrayOf(boundsMin.z, boundsMax.z)) {
                    val dx = x - centre.x
                    val dy = y - centre.y
                    val dz = z - centre.z
                    furthest = max(furthest, sqrt(dx * dx + dy * dy + dz * dz))
                }
            }
        }
        return furthest + RADIUS_PADDING
    }
}
