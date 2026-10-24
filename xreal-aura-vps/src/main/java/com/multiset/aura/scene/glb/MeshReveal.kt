/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

import com.multiset.sdk.model.Vec3
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The MultiSet sample's radial reveal (`MeshRenderer`, `MeshRevealTiming`, `radial_reveal.mat`),
 * evaluated per mesh shell instead of per pixel. Each sweep grows from the wearer to the far corner
 * of the mesh bounds behind a fading orange front, holds for [DELAY_BETWEEN_LOOPS_S], then sweeps
 * again from wherever the wearer is.
 *
 * Unlike the shader's sphere around the camera, distances run along the floor ([floorDistance]): a
 * ring that starts at the wearer's feet. The sphere first touched the floor 1.5 m below the head, so
 * each sweep began with a second of nothing and then a floor disc that popped in whole.
 */
object MeshReveal {
    private const val SMALL_RADIUS = 10f
    private const val SMALL_RADIUS_DURATION = 6f
    private const val LARGE_RADIUS = 200f
    private const val LARGE_RADIUS_DURATION = 22f
    private const val RADIUS_PADDING = 0.5f
    private const val GLOW_FRACTION = 0.1f
    private const val MIN_GLOW_WIDTH_M = 0.1f

    const val DELAY_BETWEEN_LOOPS_S = 20f

    /** How far the wearer may stand from the last cut's centre before a sweep cuts the mesh again. */
    const val RECUT_DISTANCE_M = 0.5f

    /** Steps the shader's glow fade is cut into; the GLB carries one glow twin per step per shell. */
    const val GLOW_LEVELS = 3

    /** A shell's look: [HIDDEN], [SHOWN] (plain grid), or a glow level from 1 to [GLOW_LEVELS]. */
    const val HIDDEN = -1
    const val SHOWN = 0

    /** Seconds for the front to reach [radius] metres, through (10 m, 6 s) and (200 m, 22 s). */
    fun duration(radius: Float): Float {
        val r = max(radius, 1f)
        val exponent = ln(LARGE_RADIUS_DURATION / SMALL_RADIUS_DURATION) / ln(LARGE_RADIUS / SMALL_RADIUS)
        return SMALL_RADIUS_DURATION / SMALL_RADIUS.pow(exponent) * r.pow(exponent)
    }

    /** Floor distance from [centre] to the furthest corner of the chunks' joint bounds, plus padding. */
    fun maxRadius(centre: Vec3, chunks: List<ChunkBounds>): Float {
        if (chunks.isEmpty()) return SMALL_RADIUS
        var furthest = 0f
        for (x in floatArrayOf(chunks.minOf { it.min.x }, chunks.maxOf { it.max.x })) {
            for (z in floatArrayOf(chunks.minOf { it.min.z }, chunks.maxOf { it.max.z })) {
                furthest = max(furthest, floorDistance(x - centre.x, z - centre.z))
            }
        }
        return furthest + RADIUS_PADDING
    }

    /** The shader's glow: 1 at the front, fading quadratically over max(10% of the radius, 0.1 m). */
    fun glow(distance: Float, radius: Float): Float {
        val width = max(radius * GLOW_FRACTION, MIN_GLOW_WIDTH_M)
        val fade = 1f - ((radius - distance) / width).coerceIn(0f, 1f)
        return fade * fade
    }

    /** How a shell at [distance] looks with the front at [radius]; the shader discards beyond it. */
    fun look(distance: Float, radius: Float): Int =
        if (distance > radius) HIDDEN else (glow(distance, radius) * GLOW_LEVELS).roundToInt()

    fun needsRecut(wearer: Vec3, cutCentre: Vec3, distance: Float = RECUT_DISTANCE_M): Boolean =
        floorDistance(wearer, cutCentre) > distance

    /** Distance in the map's horizontal plane; the map is Y-up. */
    fun floorDistance(a: Vec3, b: Vec3): Float = floorDistance(a.x - b.x, a.z - b.z)

    fun floorDistance(dx: Float, dz: Float): Float = sqrt(dx * dx + dz * dz)
}

/** One sweep started at [startNanos] out to [maxRadius], then the hold before the next one. */
class MeshRevealSweep private constructor(
    private val maxRadius: Float,
    private val startNanos: Long,
    private val duration: Float,
) {
    constructor(maxRadius: Float, startNanos: Long) : this(maxRadius, startNanos, MeshReveal.duration(maxRadius))

    /** This sweep on a new cut: the same start and pace, out to the new cut's [maxRadius]. */
    fun resized(maxRadius: Float) = MeshRevealSweep(maxRadius, startNanos, duration)

    /** The front's radius; it stays at [maxRadius] through the hold, as the shader's parameter does. */
    fun radius(nowNanos: Long): Float = (seconds(nowNanos) / duration).coerceIn(0f, 1f) * maxRadius

    fun isOver(nowNanos: Long): Boolean = seconds(nowNanos) >= duration + MeshReveal.DELAY_BETWEEN_LOOPS_S

    private fun seconds(nowNanos: Long) = (nowNanos - startNanos) / 1e9f
}
