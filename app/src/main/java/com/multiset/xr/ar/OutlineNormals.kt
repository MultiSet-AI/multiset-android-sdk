/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Port of iOS `OutlineMeshRenderer.smoothNormals`.
 *
 * Angle-weighted vertex normals welded across duplicated vertices on a 0.1 mm grid: scans split
 * vertices along texture seams, and normals that stop at a seam would open a gap in the outline
 * shell. The service's object meshes carry no normals, so the outline derives them here.
 */
object OutlineNormals {

    private const val WELD_SCALE = 10_000f

    /** One unit normal per position, flat `xyz`; a vertex no triangle touches gets (0, 1, 0). */
    fun smoothNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val vertexCount = positions.size / 3
        val weldIndex = IntArray(vertexCount)
        val buckets = HashMap<Triple<Int, Int, Int>, Int>()
        val accumulated = ArrayList<FloatArray>()
        for (i in 0 until vertexCount) {
            val key = Triple(weldKey(positions[i * 3]), weldKey(positions[i * 3 + 1]), weldKey(positions[i * 3 + 2]))
            weldIndex[i] = buckets.getOrPut(key) {
                accumulated += FloatArray(3)
                accumulated.size - 1
            }
        }

        var i = 0
        while (i + 2 < indices.size) {
            val a = indices[i]
            val b = indices[i + 1]
            val c = indices[i + 2]
            i += 3
            val inRange = a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount
            if (inRange) accumulateFace(positions, weldIndex, accumulated, a, b, c)
        }

        val out = FloatArray(vertexCount * 3)
        for (v in 0 until vertexCount) {
            val n = accumulated[weldIndex[v]]
            val lengthSquared = dot(n, n)
            if (lengthSquared > 1e-12f) {
                val inv = 1f / sqrt(lengthSquared)
                out[v * 3] = n[0] * inv
                out[v * 3 + 1] = n[1] * inv
                out[v * 3 + 2] = n[2] * inv
            } else {
                out[v * 3 + 1] = 1f
            }
        }
        return out
    }

    private fun accumulateFace(positions: FloatArray, weldIndex: IntArray, accumulated: List<FloatArray>, a: Int, b: Int, c: Int) {
        val ab = sub(positions, b, a)
        val ac = sub(positions, c, a)
        val face = cross(ab, ac)
        val faceLengthSquared = dot(face, face)
        if (faceLengthSquared <= 1e-18f) return
        val unit = scale(face, 1f / sqrt(faceLengthSquared))
        add(accumulated[weldIndex[a]], unit, angle(ab, ac))
        add(accumulated[weldIndex[b]], unit, angle(sub(positions, c, b), sub(positions, a, b)))
        add(accumulated[weldIndex[c]], unit, angle(sub(positions, a, c), sub(positions, b, c)))
    }

    /** Swift's `.rounded()`: to nearest, halves away from zero. */
    private fun weldKey(value: Float): Int {
        val scaled = value * WELD_SCALE
        return (sign(scaled) * floor(abs(scaled) + 0.5f)).toInt()
    }

    private fun sub(p: FloatArray, a: Int, b: Int) =
        floatArrayOf(p[a * 3] - p[b * 3], p[a * 3 + 1] - p[b * 3 + 1], p[a * 3 + 2] - p[b * 3 + 2])

    private fun cross(u: FloatArray, v: FloatArray) =
        floatArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])

    private fun dot(u: FloatArray, v: FloatArray) = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]

    private fun scale(u: FloatArray, s: Float) = floatArrayOf(u[0] * s, u[1] * s, u[2] * s)

    private fun add(target: FloatArray, unit: FloatArray, weight: Float) {
        target[0] += unit[0] * weight
        target[1] += unit[1] * weight
        target[2] += unit[2] * weight
    }

    private fun angle(u: FloatArray, v: FloatArray): Float {
        val denominator = sqrt(dot(u, u)) * sqrt(dot(v, v))
        if (denominator <= 1e-12f) return 0f
        return acos((dot(u, v) / denominator).coerceIn(-1f, 1f))
    }
}
