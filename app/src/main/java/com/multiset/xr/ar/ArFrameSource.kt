/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import android.content.Context
import android.content.res.Configuration
import android.media.Image
import android.util.Log
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ux.ArFragment
import com.multiset.sdk.camera.ImageProcessor
import com.multiset.sdk.model.CameraFrame
import com.multiset.sdk.model.CameraIntrinsics
import com.multiset.sdk.model.DeviceOrientation
import com.multiset.sdk.model.Quat
import com.multiset.sdk.model.Vec3
import com.multiset.sdk.source.FrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the latest ARCore frame into a core CameraFrame. The single AR-aware adapter. */
class ArFrameSource(
    private val arFragment: ArFragment,
    private val context: Context,
    private val imageProcessor: ImageProcessor,
) : FrameSource {

    companion object { private const val TAG = "ArFrameSource" }

    override suspend fun acquire(): CameraFrame? = withContext(Dispatchers.Main) {
        val frame = try { arFragment.arSceneView.arFrame } catch (e: Exception) { null }
        if (frame == null) {
            Log.d(TAG, "acquire: no AR frame yet")
            return@withContext null
        }
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            Log.d(TAG, "acquire: camera not TRACKING (state=${camera.trackingState})")
            return@withContext null
        }

        val pose = camera.pose
        val position = Vec3(pose.tx(), pose.ty(), pose.tz())
        val rotation = Quat(pose.qx(), pose.qy(), pose.qz(), pose.qw())

        val intr = camera.imageIntrinsics

        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            // NotYetAvailableException is transient (no new CPU image this frame); others are real.
            Log.d(TAG, "acquire: acquireCameraImage failed (${e.javaClass.simpleName}: ${e.message})")
            return@withContext null
        }

        // Read width/height into locals ON Main, before handing image to off-thread conversion
        val imgWidth = image.width
        val imgHeight = image.height

        val orientation =
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                DeviceOrientation.LANDSCAPE else DeviceOrientation.PORTRAIT

        // Move heavy YUV→Bitmap conversion off the Main thread; close image exactly once afterward
        val bitmap = try {
            withContext(Dispatchers.Default) { imageProcessor.yuvToBitmap(image) }
        } finally {
            // Runs after yuvToBitmap completes (or throws), before image planes are accessed again
            image.close()
        }

        CameraFrame(
            bitmap = bitmap,
            cameraPosition = position,
            cameraRotation = rotation,
            intrinsics = CameraIntrinsics(
                fx = intr.focalLength[0],
                fy = intr.focalLength[1],
                cx = intr.principalPoint[0],
                cy = intr.principalPoint[1],
                imageWidth = imgWidth,
                imageHeight = imgHeight,
            ),
            orientation = orientation,
            // ARCore's frame clock is monotonic; wall-clock time can jump and would skew the gate.
            timestampSeconds = frame.timestamp / 1_000_000_000.0,
        )
    }
}
