/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping.pointcloud

/** Projects world-space points to screen pixels with ARCore's column-major view and projection matrices. */
object PointProjection {

    /**
     * Writes (x, y) pixels to [out] for each of the first [count] (x, y, z) [world] points that lie in front
     * of the camera and inside the frustum, packed from the start; returns how many were written.
     */
    fun project(
        world: FloatArray,
        count: Int,
        view: FloatArray,
        projection: FloatArray,
        width: Int,
        height: Int,
        out: FloatArray,
    ): Int {
        val m = multiply(projection, view)
        var written = 0
        for (i in 0 until count) {
            val x = world[i * 3]
            val y = world[i * 3 + 1]
            val z = world[i * 3 + 2]
            val w = m[3] * x + m[7] * y + m[11] * z + m[15]
            if (!(w > 0f)) continue
            val ndcX = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w
            val ndcY = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w
            val ndcZ = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w
            if (ndcX !in -1f..1f || ndcY !in -1f..1f || ndcZ !in -1f..1f) continue
            out[written * 2] = (ndcX + 1f) / 2f * width
            out[written * 2 + 1] = (1f - ndcY) / 2f * height
            written++
        }
        return written
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = sum
        }
        return r
    }
}
