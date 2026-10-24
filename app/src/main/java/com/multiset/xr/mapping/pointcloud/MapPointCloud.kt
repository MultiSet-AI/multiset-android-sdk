/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping.pointcloud

import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Receives the accumulated cloud when a batch is due. */
interface MapPointSink {
    fun sync(accumulator: PointCloudAccumulator)
}

/** Samples ARCore points while [accumulate] is on, filters them and feeds the voxel cloud. */
class MapPointCloud(
    capacity: Int = PointCloudAccumulator.CAPACITY,
    private val uploadEveryFrames: Int = UPLOAD_EVERY_FRAMES,
) {
    val accumulator = PointCloudAccumulator(capacity = capacity)
    private var filter = FeaturePointFilter()
    private var points = FloatArray(0)
    private var ids = IntArray(0)
    private var confirmed = FloatArray(0)
    private var lastSampleNanos = NO_SAMPLE
    private var framesSinceUpload = 0
    private var uploadDue = false

    var accumulate = false
        set(value) {
            if (field == value) return
            field = value
            lastSampleNanos = NO_SAMPLE
            uploadDue = true
        }

    var sink: MapPointSink? = null
        set(value) {
            field = value
            uploadDue = true
        }

    val size: Int get() = accumulator.size

    fun isSampleDue(timestampNanos: Long): Boolean =
        accumulate && (lastSampleNanos == NO_SAMPLE || timestampNanos - lastSampleNanos >= SAMPLE_INTERVAL_NANOS)

    fun onCloud(points: FloatBuffer, ids: IntBuffer, timestampNanos: Long, camera: FloatArray) {
        if (!isSampleDue(timestampNanos)) return
        val floats = points.remaining()
        val count = minOf(floats / 4, ids.remaining())
        if (this.points.size < floats) this.points = FloatArray(floats)
        if (this.ids.size < count) this.ids = IntArray(count)
        points.duplicate().get(this.points, 0, floats)
        ids.duplicate().get(this.ids, 0, count)
        onPoints(this.points, this.ids, count, timestampNanos, camera)
    }

    fun onPoints(points: FloatArray, ids: IntArray, count: Int, timestampNanos: Long, camera: FloatArray) {
        if (!isSampleDue(timestampNanos)) return
        lastSampleNanos = timestampNanos
        if (confirmed.size < count * 3) confirmed = FloatArray(count * 3)
        val surfacePoints = filter.observe(ids, points, count, camera, confirmed)
        if (surfacePoints == 0) return
        if (accumulator.add(confirmed, surfacePoints) > 0 || accumulator.didCoarsen) uploadDue = true
    }

    fun clear() {
        accumulator.clear()
        filter = FeaturePointFilter()
        lastSampleNanos = NO_SAMPLE
        uploadDue = true
    }

    fun flush() {
        val sink = sink ?: return
        framesSinceUpload++
        if (uploadDue && framesSinceUpload >= uploadEveryFrames) {
            sink.sync(accumulator)
            uploadDue = false
            framesSinceUpload = 0
        }
    }

    companion object {
        const val UPLOAD_EVERY_FRAMES = 4
        // 60 ms takes every other frame at 30 fps (about 15 Hz) despite jitter.
        const val SAMPLE_INTERVAL_NANOS = 60_000_000L

        private const val NO_SAMPLE = Long.MIN_VALUE
    }
}
