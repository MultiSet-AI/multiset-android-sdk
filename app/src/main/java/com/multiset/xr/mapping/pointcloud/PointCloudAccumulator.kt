/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping.pointcloud

import kotlin.math.floor

/** The coverage cloud: one point per voxel, coarsening the grid when full. */
class PointCloudAccumulator(
    private val initialVoxelSize: Float = VOXEL_SIZE,
    val capacity: Int = CAPACITY,
    private val maximumVoxelSize: Float = MAXIMUM_VOXEL_SIZE,
) {
    val positions = FloatArray(capacity * 3)

    var size = 0
        private set

    var voxelSize = initialVoxelSize
        private set

    var didCoarsen = false
        private set

    var generation = 0
        private set

    private val voxels = VoxelSet(capacity)

    fun add(points: FloatArray, count: Int): Int {
        didCoarsen = false
        var added = 0
        for (i in 0 until count) {
            val base = i * 3
            if (admit(points[base], points[base + 1], points[base + 2])) {
                points.copyInto(positions, destinationOffset = size * 3, startIndex = base, endIndex = base + 3)
                size++
                added++
            }
        }
        return added
    }

    private fun admit(x: Float, y: Float, z: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (voxels.contains(voxelKey(x, y, z))) return false
        if (size >= capacity) {
            if (!rebucket()) return false
            didCoarsen = true
            if (size >= capacity) return false
        }
        return voxels.add(voxelKey(x, y, z))
    }

    fun clear() {
        size = 0
        voxelSize = initialVoxelSize
        didCoarsen = false
        generation++
        voxels.clear()
    }

    private fun rebucket(): Boolean {
        val next = voxelSize * 2
        if (next > maximumVoxelSize * COARSEST_SLACK) return false
        voxelSize = next
        voxels.clear()
        val previous = size
        size = 0
        for (i in 0 until previous) {
            val base = i * 3
            if (voxels.add(voxelKey(positions[base], positions[base + 1], positions[base + 2]))) {
                positions.copyInto(positions, destinationOffset = size * 3, startIndex = base, endIndex = base + 3)
                size++
            }
        }
        generation++
        return true
    }

    private fun voxelKey(x: Float, y: Float, z: Float): Long =
        (cell(x) shl (AXIS_BITS * 2)) or (cell(y) shl AXIS_BITS) or cell(z)

    // 21 bits per axis wraps every ~63 km at 3 cm, far beyond one ARCore session.
    private fun cell(value: Float): Long = floor(value / voxelSize).toLong() and AXIS_MASK

    companion object {
        const val VOXEL_SIZE = 0.03f
        const val MAXIMUM_VOXEL_SIZE = 0.12f
        const val CAPACITY = 30_000
        private const val COARSEST_SLACK = 1.0001f
        private const val AXIS_BITS = 21
        private const val AXIS_MASK = (1L shl AXIS_BITS) - 1
    }
}

private class VoxelSet(expected: Int) {
    private val table = LongArray(Integer.highestOneBit(maxOf(expected, 1) * 2 - 1) shl 1).also { it.fill(EMPTY) }
    private val mask = table.size - 1

    fun add(key: Long): Boolean {
        val slot = find(key)
        if (table[slot] == key) return false
        table[slot] = key
        return true
    }

    fun contains(key: Long): Boolean = table[find(key)] == key

    private fun find(key: Long): Int {
        var slot = mix(key) and mask
        while (table[slot] != EMPTY && table[slot] != key) slot = (slot + 1) and mask
        return slot
    }

    fun clear() = table.fill(EMPTY)

    private fun mix(key: Long): Int {
        val h = key * -0x61c8864680b583ebL
        return (h xor (h ushr 32)).toInt()
    }

    private companion object {
        const val EMPTY = -1L
    }
}
