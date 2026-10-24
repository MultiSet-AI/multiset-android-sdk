/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping

import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.sdk.mapping.MappingCameraImage
import com.multiset.sdk.mapping.MappingLocationProvider
import com.multiset.sdk.mapping.MappingRGBFrame
import com.multiset.sdk.mapping.MappingTrackingSample
import com.multiset.sdk.mapping.MultiSetMappingSource
import com.multiset.sdk.model.CameraIntrinsics
import com.multiset.sdk.model.Quat
import com.multiset.sdk.model.Vec3
import com.multiset.xr.mapping.pointcloud.MapPointCloud
import com.multiset.xr.mapping.pointcloud.MapPointSink

/**
 * The ARCore adapter for [MultiSetMappingSource]. Every call is on the main thread.
 *
 * The host calls [onSceneUpdate] from the scene's update and [onDepthSupport] from the session configuration;
 * [onTrackingSample] receives each update's sample, for `MultiSetMapping.reportTrackingSample`.
 */
class ArMappingSource(
    private val arFragment: ArFragment,
    pointSink: MapPointSink?,
    override val locationProvider: MappingLocationProvider? = null,
    private val onTrackingSample: (MappingTrackingSample) -> Unit,
) : MultiSetMappingSource {
    val points = MapPointCloud().also { it.sink = pointSink }

    override var depthSupported = false
        private set

    private var hasTracked = false
    private val cameraPosition = FloatArray(3)

    fun onDepthSupport(supported: Boolean) {
        depthSupported = supported
    }

    fun onSceneUpdate(frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) hasTracked = true
        val features = if (tracking) readPoints(frame) else 0
        points.flush()
        val pose = camera.pose
        onTrackingSample(
            MappingTrackingSample(
                trackingState = MappingTrackingReadings.of(camera.trackingState, camera.trackingFailureReason, hasTracked),
                position = Vec3(pose.tx(), pose.ty(), pose.tz()),
                timestamp = frame.timestamp / NANOS_PER_SECOND,
                featurePointCount = features,
                pixelIntensity = pixelIntensity(frame),
            ),
        )
    }

    override fun startPreview() {
        points.accumulate = false
    }

    override fun startRecording() {
        points.clear()
        points.accumulate = true
    }

    override fun pause() {
        points.accumulate = false
    }

    override suspend fun acquireRGBFrame(): MappingRGBFrame? {
        val frame = currentFrame() ?: return null
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val pose = camera.pose
        return ArRgbFrame(
            frame = frame,
            position = Vec3(pose.tx(), pose.ty(), pose.tz()),
            rotation = Quat(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
            timestamp = frame.timestamp / NANOS_PER_SECOND,
            hasDepth = depthSupported && depthAvailable(frame),
        )
    }

    private fun currentFrame(): Frame? = arFragment.arSceneView?.arFrame

    private fun depthAvailable(frame: Frame): Boolean = try {
        frame.acquireDepthImage16Bits().close()
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun readPoints(frame: Frame): Int = try {
        frame.acquirePointCloud().use { cloud ->
            if (points.isSampleDue(frame.timestamp)) {
                frame.camera.pose.getTranslation(cameraPosition, 0)
                points.onCloud(cloud.points, cloud.ids, cloud.timestamp, cameraPosition)
            }
            cloud.ids.remaining()
        }
    } catch (_: RuntimeException) {
        0
    }

    private fun pixelIntensity(frame: Frame): Float? = try {
        frame.lightEstimate.takeIf { it.state == LightEstimate.State.VALID }?.pixelIntensity
    } catch (_: RuntimeException) {
        null
    }

    private inner class ArRgbFrame(
        private val frame: Frame,
        override val position: Vec3,
        override val rotation: Quat,
        override val timestamp: Double,
        override val hasDepth: Boolean,
    ) : MappingRGBFrame {
        override fun readImage(): MappingCameraImage? {
            if (currentFrame()?.timestamp != frame.timestamp) return null
            var camera: Image? = null
            var depth: Image? = null
            return try {
                camera = frame.acquireCameraImage()
                if (hasDepth) depth = frame.acquireDepthImage16Bits()
                val intrinsics = frame.camera.imageIntrinsics
                MappingCameraImage.copyOf(
                    cameraImage = camera,
                    intrinsics = CameraIntrinsics(
                        fx = intrinsics.focalLength[0],
                        fy = intrinsics.focalLength[1],
                        cx = intrinsics.principalPoint[0],
                        cy = intrinsics.principalPoint[1],
                        imageWidth = intrinsics.imageDimensions[0],
                        imageHeight = intrinsics.imageDimensions[1],
                    ),
                    depthImage = depth,
                )
            } catch (_: RuntimeException) {
                null
            } finally {
                depth?.close()
                camera?.close()
            }
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
    }
}
