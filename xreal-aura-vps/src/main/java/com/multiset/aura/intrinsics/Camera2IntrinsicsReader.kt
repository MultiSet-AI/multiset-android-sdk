/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.intrinsics

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.os.Build

class IntrinsicsReport(
    val outcome: IntrinsicsOutcome,
    val perFrameCalibration: FloatArray?,
    val staticCalibration: FloatArray?,
    val rawDistortion: FloatArray?,
    val focalLengthMm: Float?,
    val distortionCorrectionMode: String,
    val rotateAndCropMode: String,
    val warnings: List<String>,
)

/** Translates Camera2 metadata for one frame into [CameraIntrinsicsCalculator] input. */
object Camera2IntrinsicsReader {

    fun read(ch: CameraCharacteristics, cameraId: String, result: CaptureResult, width: Int, height: Int): IntrinsicsReport {
        val warnings = mutableListOf<String>()
        val perFrameK = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
        val staticK = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val rawDistortion = result.get(CaptureResult.LENS_DISTORTION)
            ?: ch.get(CameraCharacteristics.LENS_DISTORTION)
        val focalMm = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            ?: ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

        val supportsCorrection = ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            ?.any { it != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF } == true
        val correctionMode = result.get(CaptureResult.DISTORTION_CORRECTION_MODE)
        val correctionApplied = supportsCorrection && correctionMode != null &&
            correctionMode != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF
        if (correctionMode == CameraMetadata.DISTORTION_CORRECTION_MODE_FAST) {
            warnings += "Distortion correction ran in FAST mode, which some devices implement as OFF."
        }

        val rotateAndCrop = result.get(CaptureResult.SCALER_ROTATE_AND_CROP)
        if (rotateAndCrop != null && rotateAndCrop != CameraMetadata.SCALER_ROTATE_AND_CROP_NONE) {
            warnings += "The framework rotated/cropped the frame (SCALER_ROTATE_AND_CROP=$rotateAndCrop); " +
                "intrinsics assume no rotation and are likely wrong."
        }

        val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val outcome = if (pixelArray == null || activeArray == null) {
            IntrinsicsOutcome.Unavailable("The camera does not report its sensor geometry (SENSOR_INFO_*).")
        } else {
            val preCorrection = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: activeArray
            val physical = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val reported = perFrameK.toCalibration() ?: staticK.toCalibration()
            val profile = CalibrationProfiles.find(Build.MANUFACTURER, Build.MODEL, cameraId, activeArray.toPixelRect())
            CameraIntrinsicsCalculator.compute(
                IntrinsicsInput(
                    sensor = SensorGeometry(
                        pixelArrayWidth = pixelArray.width,
                        pixelArrayHeight = pixelArray.height,
                        preCorrectionActiveArray = preCorrection.toPixelRect(),
                        activeArray = activeArray.toPixelRect(),
                        physicalWidthMm = physical?.width,
                        physicalHeightMm = physical?.height,
                    ),
                    reportedCalibration = reported,
                    reportedCalibrationIsPerFrame = perFrameK.toCalibration() != null,
                    profileCalibration = profile?.calibration,
                    focalLengthMm = focalMm,
                    distortion = rawDistortion.toDistortion(),
                    distortionCorrectionApplied = correctionApplied,
                    coordinateSystem = if (supportsCorrection && !correctionApplied) {
                        StreamCoordinateSystem.PRE_CORRECTION_ACTIVE_ARRAY
                    } else {
                        StreamCoordinateSystem.ACTIVE_ARRAY
                    },
                    cropRegion = result.get(CaptureResult.SCALER_CROP_REGION)?.toPixelRect(),
                    zoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f,
                    outputWidth = width,
                    outputHeight = height,
                )
            )
        }

        return IntrinsicsReport(
            outcome = outcome,
            perFrameCalibration = perFrameK,
            staticCalibration = staticK,
            rawDistortion = rawDistortion,
            focalLengthMm = focalMm,
            distortionCorrectionMode = when (correctionMode) {
                null -> if (supportsCorrection) "UNREPORTED" else "UNSUPPORTED"
                CameraMetadata.DISTORTION_CORRECTION_MODE_OFF -> "OFF"
                CameraMetadata.DISTORTION_CORRECTION_MODE_FAST -> "FAST"
                CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
                else -> "UNKNOWN($correctionMode)"
            },
            rotateAndCropMode = when (rotateAndCrop) {
                null -> "UNREPORTED"
                CameraMetadata.SCALER_ROTATE_AND_CROP_NONE -> "NONE"
                else -> rotateAndCrop.toString()
            },
            warnings = warnings,
        )
    }

    // Some HALs publish an all-zero array instead of omitting the key.
    private fun FloatArray?.toCalibration(): LensCalibration? =
        this?.takeIf { it.size >= 5 && it[0] > 0f && it[1] > 0f }
            ?.let { LensCalibration(it[0].asDecimal(), it[1].asDecimal(), it[2].asDecimal(), it[3].asDecimal(), it[4].asDecimal()) }

    private fun FloatArray?.toDistortion(): LensDistortion? =
        this?.takeIf { it.size >= 5 }
            ?.let { LensDistortion(it[0].asDecimal(), it[1].asDecimal(), it[2].asDecimal(), it[3].asDecimal(), it[4].asDecimal()) }

    private fun Rect.toPixelRect() = PixelRect(left, top, width(), height())
}

/** Widens a Float via its shortest decimal form, so 0.1f becomes 0.1 rather than 0.10000000149. */
internal fun Float.asDecimal(): Double = toString().toDouble()
