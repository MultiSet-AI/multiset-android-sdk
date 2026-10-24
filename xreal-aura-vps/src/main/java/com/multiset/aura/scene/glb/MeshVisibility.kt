/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

import com.multiset.sdk.model.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Which of a mesh's triangles can be seen from one point. On a see-through display nothing real hides
 * the mesh, so without this the rooms behind the wall in front of the wearer draw over it. From one
 * spot on Aura, 53% of the map's triangles were behind a nearer surface. A cube-map depth buffer is
 * rendered around the eye, and each triangle is kept when its centroid is not behind it.
 */
object MeshVisibility {

    /** How far the wearer may walk from the viewpoint of the last cut before it is cut again. */
    const val RECUT_DISTANCE_M = 1f

    /** Pixels per cube face side: about 2 cm at 5 m. */
    const val FACE_SIZE = 256

    private const val NEAR_M = 0.05f

    // Room for the depth buffer's resolution, so a surface never hides itself or what lies on it.
    private const val SLACK_M = 0.08f
    private const val SLACK_FRACTION = 0.03f

    /** One flag per triangle of each primitive (xyz positions, triangle indices): true if visible. */
    fun visible(primitives: List<Pair<FloatArray, IntArray>>, eye: Vec3): List<BooleanArray> {
        val depth = Array(FACES) { FloatArray(FACE_SIZE * FACE_SIZE) { Float.POSITIVE_INFINITY } }
        val x = FloatArray(3)
        val y = FloatArray(3)
        val z = FloatArray(3)
        for ((positions, indices) in primitives) {
            for (t in 0 until indices.size / 3) {
                for (face in 0 until FACES) {
                    if (project(positions, indices, t, eye, face, x, y, z)) rasterize(depth[face], x, y, z)
                }
            }
        }
        return primitives.map { (positions, indices) ->
            BooleanArray(indices.size / 3) { t -> centroidVisible(positions, indices, t, eye, depth) }
        }
    }

    private const val FACES = 6

    /** Face f looks along axis f / 2, positive for even f; false when a vertex is behind its near plane. */
    private fun project(
        p: FloatArray, indices: IntArray, t: Int, eye: Vec3, face: Int,
        x: FloatArray, y: FloatArray, z: FloatArray,
    ): Boolean {
        val axis = face / 2
        val sign = if (face % 2 == 0) 1f else -1f
        for (k in 0 until 3) {
            val v = indices[t * 3 + k] * 3
            val d0 = p[v] - eye.x
            val d1 = p[v + 1] - eye.y
            val d2 = p[v + 2] - eye.z
            val depth = sign * component(d0, d1, d2, axis)
            if (depth <= NEAR_M) return false
            x[k] = toPixel(component(d0, d1, d2, (axis + 1) % 3) / depth)
            y[k] = toPixel(component(d0, d1, d2, (axis + 2) % 3) / depth)
            z[k] = depth
        }
        return true
    }

    private fun component(d0: Float, d1: Float, d2: Float, axis: Int) = when (axis) {
        0 -> d0
        1 -> d1
        else -> d2
    }

    private fun toPixel(tangent: Float) = (tangent + 1f) * 0.5f * FACE_SIZE

    /** Keeps the nearest depth per pixel; 1/z is interpolated, as it is linear on screen. */
    private fun rasterize(buffer: FloatArray, x: FloatArray, y: FloatArray, z: FloatArray) {
        val minX = max(0, floor(min(x[0], min(x[1], x[2]))).toInt())
        val maxX = min(FACE_SIZE - 1, ceil(max(x[0], max(x[1], x[2]))).toInt())
        val minY = max(0, floor(min(y[0], min(y[1], y[2]))).toInt())
        val maxY = min(FACE_SIZE - 1, ceil(max(y[0], max(y[1], y[2]))).toInt())
        if (minX > maxX || minY > maxY) return
        // A triangle smaller than a pixel covers no pixel centre, but still hides what is behind it.
        splat(buffer, (x[0] + x[1] + x[2]) / 3, (y[0] + y[1] + y[2]) / 3, (z[0] + z[1] + z[2]) / 3)
        val area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0])
        if (abs(area) < 1e-6f) return
        for (py in minY..maxY) {
            val cy = py + 0.5f
            for (px in minX..maxX) {
                val cx = px + 0.5f
                val w0 = ((x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)) / area
                val w1 = ((x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val depth = 1f / (w0 / z[0] + w1 / z[1] + w2 / z[2])
                val at = py * FACE_SIZE + px
                if (depth < buffer[at]) buffer[at] = depth
            }
        }
    }

    private fun splat(buffer: FloatArray, x: Float, y: Float, depth: Float) {
        val px = x.toInt()
        val py = y.toInt()
        if (x < 0f || y < 0f || px >= FACE_SIZE || py >= FACE_SIZE) return
        val at = py * FACE_SIZE + px
        if (depth < buffer[at]) buffer[at] = depth
    }

    private fun centroidVisible(p: FloatArray, indices: IntArray, t: Int, eye: Vec3, depth: Array<FloatArray>): Boolean {
        var d0 = 0f
        var d1 = 0f
        var d2 = 0f
        for (k in 0 until 3) {
            val v = indices[t * 3 + k] * 3
            d0 += p[v]
            d1 += p[v + 1]
            d2 += p[v + 2]
        }
        d0 = d0 / 3 - eye.x
        d1 = d1 / 3 - eye.y
        d2 = d2 / 3 - eye.z
        val axis = when {
            abs(d0) >= abs(d1) && abs(d0) >= abs(d2) -> 0
            abs(d1) >= abs(d2) -> 1
            else -> 2
        }
        val along = component(d0, d1, d2, axis)
        val distance = abs(along)
        if (distance <= NEAR_M) return true
        val face = axis * 2 + if (along < 0f) 1 else 0
        val px = toPixel(component(d0, d1, d2, (axis + 1) % 3) / distance).toInt().coerceIn(0, FACE_SIZE - 1)
        val py = toPixel(component(d0, d1, d2, (axis + 2) % 3) / distance).toInt().coerceIn(0, FACE_SIZE - 1)
        val nearest = depth[face][py * FACE_SIZE + px]
        return distance <= nearest * (1f + SLACK_FRACTION) + SLACK_M
    }
}
