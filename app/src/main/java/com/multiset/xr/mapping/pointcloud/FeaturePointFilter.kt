/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping.pointcloud

import kotlin.math.cos
import kotlin.math.sqrt

const val MIN_POINT_CONFIDENCE = 0.2f

/** Admits an ARCore feature point only once it is re-observed in place from a moved viewpoint. */
class FeaturePointFilter {
    private class Track(var x: Float, var y: Float, var z: Float, val firstCamera: FloatArray, var lastSeen: Int) {
        var count = 1
    }

    private val tracks = HashMap<Int, Track>()
    private val confirmed = HashSet<Int>()
    private var sample = 0

    val confirmedCount: Int get() = confirmed.size

    fun observe(ids: IntArray, points: FloatArray, count: Int, camera: FloatArray, out: FloatArray): Int {
        sample++
        var confirmedNow = 0
        for (i in 0 until minOf(count, ids.size, points.size / 4)) {
            val base = i * 4
            if (confirms(ids[i], points, base, camera)) {
                points.copyInto(out, destinationOffset = confirmedNow * 3, startIndex = base, endIndex = base + 3)
                confirmedNow++
            }
        }
        if (sample % STALE_SAMPLES == 0) tracks.values.removeIf { sample - it.lastSeen > STALE_SAMPLES }
        return confirmedNow
    }

    private fun confirms(id: Int, points: FloatArray, base: Int, camera: FloatArray): Boolean {
        val x = points[base]
        val y = points[base + 1]
        val z = points[base + 2]
        if (id in confirmed || points[base + 3] < MIN_POINT_CONFIDENCE || !isInRange(x, y, z, camera)) return false
        val track = tracks[id]
        if (track == null || sample - track.lastSeen > STALE_SAMPLES || distance(track, x, y, z) > TOLERANCE) {
            tracks[id] = Track(x, y, z, camera.copyOf(3), sample)
            return false
        }
        track.count++
        track.x += (x - track.x) / track.count
        track.y += (y - track.y) / track.count
        track.z += (z - track.z) / track.count
        track.lastSeen = sample
        if (track.count < CONFIRMATIONS || !hasParallax(track.firstCamera, x, y, z, camera)) return false
        if (confirmed.size >= CONFIRMED_MEMORY) confirmed.clear()
        confirmed += id
        tracks -= id
        return true
    }

    private fun isInRange(x: Float, y: Float, z: Float, camera: FloatArray): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        return length(x - camera[0], y - camera[1], z - camera[2]) in MINIMUM_RANGE..MAXIMUM_RANGE
    }

    private fun hasParallax(firstCamera: FloatArray, x: Float, y: Float, z: Float, camera: FloatArray): Boolean {
        val fx = firstCamera[0] - x
        val fy = firstCamera[1] - y
        val fz = firstCamera[2] - z
        val nx = camera[0] - x
        val ny = camera[1] - y
        val nz = camera[2] - z
        val lengths = length(fx, fy, fz) * length(nx, ny, nz)
        return lengths > 0f && (fx * nx + fy * ny + fz * nz) / lengths <= MINIMUM_PARALLAX_COSINE
    }

    private fun distance(track: Track, x: Float, y: Float, z: Float) = length(track.x - x, track.y - y, track.z - z)

    private fun length(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    companion object {
        // Looser than the iOS filter (3 sightings, 3 cm, 3 deg): it left the cloud too sparse on ARCore.
        const val CONFIRMATIONS = 2
        const val TOLERANCE = 0.05f
        const val MINIMUM_RANGE = 0.25f
        const val MAXIMUM_RANGE = 5.0f

        const val STALE_SAMPLES = 15

        const val MINIMUM_PARALLAX_DEGREES = 1.5f

        const val CONFIRMED_MEMORY = 50_000

        private val MINIMUM_PARALLAX_COSINE = cos(Math.toRadians(MINIMUM_PARALLAX_DEGREES.toDouble())).toFloat()
    }
}
