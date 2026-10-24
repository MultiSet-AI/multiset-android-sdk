/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.frame

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import com.multiset.aura.camera.CameraHub
import com.multiset.aura.camera.CaptureException
import com.multiset.aura.intrinsics.Camera2IntrinsicsReader
import com.multiset.aura.intrinsics.IntrinsicsOutcome
import com.multiset.aura.intrinsics.IntrinsicsSource
import com.multiset.aura.pose.HeadPoseSource
import com.multiset.aura.pose.PoseLookup
import com.multiset.aura.pose.readExposureTiming
import com.multiset.sdk.model.CameraFrame
import com.multiset.sdk.model.CameraIntrinsics
import com.multiset.sdk.source.FrameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Feeds the MultiSet SDK from the glasses' world camera: one still per [acquire], taken through the
 * shared [CameraHub], scaled whole (never cropped) to the 1280x720 frame the SDK passes through
 * unscaled, and paired with the head pose at its exposure.
 */
class AuraFrameSource(
    private val camera: CameraHub,
    private val headPose: HeadPoseSource,
    private val displayRotation: () -> Int,
) : FrameSource {

    private val _lastProblem = MutableStateFlow<String?>(null)

    /** Why the last [acquire] gave no frame, or a warning on the one it gave; null when clean. */
    val lastProblem: StateFlow<String?> = _lastProblem.asStateFlow()

    private val _lastFrame = MutableStateFlow<FrameReport?>(null)

    /** The latest frame handed to the SDK. */
    val lastFrame: StateFlow<FrameReport?> = _lastFrame.asStateFlow()

    // The SDK calls this on the main thread; resampling the still is heavy, so it runs elsewhere.
    override suspend fun acquire(): CameraFrame? = withContext(Dispatchers.Default) {
        try {
            captureFrame()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem(CaptureException.from(e).message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun captureFrame(): CameraFrame? {
        val startedAt = SystemClock.elapsedRealtime()
        val still = camera.takeStill()
        val frame = still.frame
        val ch = still.camera.characteristics

        val timing = readExposureTiming(frame.result, ch) ?: return problem("The camera reported no sensor timestamp.")
        if (!frame.imageTimestampMatchesResult) {
            Log.w(TAG, "The still's image timestamp differs from its capture result; its pose may be off.")
        }
        val exposureNanos = timing.midpointMonotonicNanos(SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        val head = when (val lookup = headPose.poseAt(exposureNanos)) {
            is PoseLookup.Found -> lookup
            is PoseLookup.Unavailable -> return problem("No head pose for the frame: ${lookup.reason}")
        }
        val streamIntrinsics = when (val outcome = Camera2IntrinsicsReader.read(ch, frame.cameraId, frame.result, frame.width, frame.height).outcome) {
            is IntrinsicsOutcome.Available -> outcome.intrinsics
            is IntrinsicsOutcome.Unavailable -> return problem("No camera intrinsics: ${outcome.reason}")
        }

        val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = displayRotation()
        val framePlan = AuraFramePlan.create(frame.width, frame.height, sensorOrientation, rotation)
        val plan = framePlan.resample
        val sdkPlan = framePlan.sdk
        if (plan.outputWidth < plan.outputHeight) {
            return problem("The camera stream is ${frame.width}x${frame.height} (portrait); the SDK needs a landscape frame.")
        }
        val k = plan.transform(streamIntrinsics)
        val estimated = k.source == IntrinsicsSource.ESTIMATED_FROM_FOCAL_LENGTH
        if (estimated) {
            Log.w(
                TAG,
                "No calibration profile matches ${Build.MANUFACTURER} ${Build.MODEL} camera ${frame.cameraId}: " +
                    "the nominal focal length misplaces the map along the view by a share of the distance."
            )
        }
        val distortionUncorrected = k.distortion != null && k.distortionAppliesToImage

        val bitmap = FrameResampler.resampleToBitmap(frame.jpegBytes, plan)
        val cameraPose = CameraPoseCalculator.savedImageCameraPose(
            devicePose = head.pose,
            headToUprightCamera = HeadToCamera.UNCALIBRATED.pose,
            sensorOrientationDegrees = sensorOrientation,
            imageRotationCwDegrees = sdkPlan.bakedRotationDegrees,
        )
        val captureMillis = SystemClock.elapsedRealtime() - startedAt
        Log.i(
            TAG,
            "Frame for VPS in $captureMillis ms: cam ${frame.cameraId} " +
                "${frame.width}x${frame.height}, baked ${sdkPlan.bakedRotationDegrees} deg, ${sdkPlan.orientation}, " +
                "pose samples ${(head.after.monotonicNanos - head.before.monotonicNanos) / 1e6} ms apart, " +
                "intrinsics ${k.source}, clock ${timing.clock}, sensor orientation $sensorOrientation, " +
                "display rotation $rotation"
        )
        // Enough to redo the SDK's result math offline: map = sent pose * inverse(server camera pose).
        Log.i(
            TAG,
            "Frame detail: sent pose t=${cameraPose.translation.format()} q=${cameraPose.rotation.format()}, " +
                "K fx=%.2f fy=%.2f cx=%.2f cy=%.2f %dx%d, distortion uncorrected=$distortionUncorrected, "
                    .format(k.fx, k.fy, k.cx, k.cy, k.width, k.height) +
                "lens pose ${lensPose(ch)}"
        )
        _lastFrame.value = FrameReport(
            width = k.width,
            height = k.height,
            orientation = sdkPlan.orientation,
            intrinsicsSource = k.source,
            fx = k.fx,
            fy = k.fy,
            cx = k.cx,
            cy = k.cy,
            sensorOrientation = sensorOrientation,
            captureMillis = captureMillis,
        )
        _lastProblem.value = if (estimated) UNCALIBRATED_PROBLEM else null
        return CameraFrame(
            bitmap = bitmap,
            cameraPosition = cameraPose.toSdkPosition(),
            cameraRotation = cameraPose.toSdkRotation(),
            intrinsics = CameraIntrinsics(k.fx.toFloat(), k.fy.toFloat(), k.cx.toFloat(), k.cy.toFloat(), k.width, k.height),
            orientation = sdkPlan.orientation,
            timestampSeconds = exposureNanos / 1e9,
        )
    }

    private fun lensPose(ch: CameraCharacteristics): String {
        val reference = when (ch.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
            CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "PRIMARY_CAMERA"
            CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE -> "GYROSCOPE"
            CameraMetadata.LENS_POSE_REFERENCE_UNDEFINED -> "UNDEFINED"
            CameraMetadata.LENS_POSE_REFERENCE_AUTOMOTIVE -> "AUTOMOTIVE"
            null -> "not reported"
            else -> "unknown"
        }
        val translation = ch.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.joinToString(prefix = "[", postfix = "]")
        val rotation = ch.get(CameraCharacteristics.LENS_POSE_ROTATION)?.joinToString(prefix = "[", postfix = "]")
        return "ref=$reference t=$translation q=$rotation"
    }

    private fun Vector3.format() = "[%.4f, %.4f, %.4f]".format(x, y, z)

    private fun Quaternion.format() = "[%.5f, %.5f, %.5f, %.5f]".format(x, y, z, w)

    private fun problem(message: String): CameraFrame? {
        Log.w(TAG, "No frame for VPS: $message")
        _lastProblem.value = message
        return null
    }

    private companion object {
        const val TAG = "AuraFrame"
        const val UNCALIBRATED_PROBLEM = "No calibration profile matches this camera; fixes will land off along the view."
    }
}
