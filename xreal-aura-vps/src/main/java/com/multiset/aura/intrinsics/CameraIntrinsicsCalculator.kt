/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.intrinsics

import kotlin.math.abs

/** Integer pixel rectangle; mirrors Camera2 `Rect` semantics without depending on android.jar. */
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/** Static sensor geometry from `CameraCharacteristics.SENSOR_INFO_*`. Rects are pixel-array relative. */
data class SensorGeometry(
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val preCorrectionActiveArray: PixelRect,
    val activeArray: PixelRect,
    val physicalWidthMm: Float?,
    val physicalHeightMm: Float?,
)

/** `LENS_INTRINSIC_CALIBRATION` values, in pre-correction active array pixels. */
data class LensCalibration(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val skew: Double,
)

/** Brown-Conrady coefficients from `LENS_DISTORTION` ([k1, k2, k3, p1, p2] in Camera2 order). */
data class LensDistortion(
    val k1: Double,
    val k2: Double,
    val k3: Double,
    val p1: Double,
    val p2: Double,
) {
    val openCvOrder: List<Double> get() = listOf(k1, k2, p1, p2, k3)
}

enum class IntrinsicsSource(val description: String) {
    REPORTED_PER_FRAME("Reported by the device for this frame (CaptureResult.LENS_INTRINSIC_CALIBRATION)"),
    REPORTED_STATIC("Reported by the device (CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)"),
    CALIBRATED_PROFILE("Measured offline for this device model (CalibrationProfiles); the device reports none"),
    ESTIMATED_FROM_FOCAL_LENGTH(
        "Estimated from nominal focal length and sensor physical size; principal point assumed at " +
            "image center. Not a calibration."
    ),
    ARCORE_IMAGE("Reported by ARCore for its CPU image (Camera.getImageIntrinsics)"),
}

/** Coordinate system Camera2 uses for the crop region of a processed (non-RAW) stream. */
enum class StreamCoordinateSystem { ACTIVE_ARRAY, PRE_CORRECTION_ACTIVE_ARRAY }

data class IntrinsicsInput(
    val sensor: SensorGeometry,
    val reportedCalibration: LensCalibration?,
    val reportedCalibrationIsPerFrame: Boolean,
    /** Offline calibration in active-array pixels, used when the device reports none. */
    val profileCalibration: LensCalibration? = null,
    val focalLengthMm: Float?,
    val distortion: LensDistortion?,
    val distortionCorrectionApplied: Boolean,
    val coordinateSystem: StreamCoordinateSystem,
    val cropRegion: PixelRect?,
    val zoomRatio: Float,
    val outputWidth: Int,
    val outputHeight: Int,
)

/** How the sensor frame was cropped and scaled to produce the saved image. */
data class OutputMapping(
    val coordinateSystem: StreamCoordinateSystem,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val cropRegion: PixelRect,
    val effectiveCropLeft: Double,
    val effectiveCropTop: Double,
    val effectiveCropWidth: Double,
    val effectiveCropHeight: Double,
    val scaleX: Double,
    val scaleY: Double,
    val zoomRatio: Float,
)

/**
 * Pinhole intrinsics in pixels of the saved image. Uses the Camera2 convention where the center of
 * pixel (i, j) is at (i + 0.5, j + 0.5); subtract 0.5 from cx/cy for OpenCV.
 */
data class FrameIntrinsics(
    val source: IntrinsicsSource,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val skew: Double,
    val width: Int,
    val height: Int,
    val distortion: LensDistortion?,
    val distortionAppliesToImage: Boolean,
    val mapping: OutputMapping,
    val warnings: List<String>,
)

sealed interface IntrinsicsOutcome {
    data class Available(val intrinsics: FrameIntrinsics) : IntrinsicsOutcome
    data class Unavailable(val reason: String) : IntrinsicsOutcome
}

object CameraIntrinsicsCalculator {

    // Above this, the HAL's crop/scale probably doesn't follow the documented centered aspect crop.
    private const val MAX_ANISOTROPIC_SCALE = 0.01

    fun compute(input: IntrinsicsInput): IntrinsicsOutcome {
        require(input.outputWidth > 0 && input.outputHeight > 0) { "Output size must be positive" }
        val warnings = mutableListOf<String>()
        val sensor = input.sensor
        val nativeFrame = when (input.coordinateSystem) {
            StreamCoordinateSystem.ACTIVE_ARRAY -> sensor.activeArray
            StreamCoordinateSystem.PRE_CORRECTION_ACTIVE_ARRAY -> sensor.preCorrectionActiveArray
        }

        val nativeK = nativeIntrinsics(input, nativeFrame)
            ?: return IntrinsicsOutcome.Unavailable(
                "Camera reports no LENS_INTRINSIC_CALIBRATION, and focal length or sensor physical " +
                    "size is missing, so intrinsics cannot be estimated."
            )
        val (source, k) = nativeK
        if (source == IntrinsicsSource.ESTIMATED_FROM_FOCAL_LENGTH && hasNonSquarePitch(sensor)) {
            warnings += "SENSOR_INFO_PHYSICAL_SIZE (${sensor.physicalWidthMm}x${sensor.physicalHeightMm} mm) " +
                "doesn't match the ${sensor.pixelArrayWidth}x${sensor.pixelArrayHeight} pixel array's " +
                "aspect; assumed square pixels with the smaller pitch."
        }

        val zoom = input.zoomRatio.toDouble()
        val zoomed = if (zoom == 1.0) k else LensCalibration(
            fx = k.fx * zoom,
            fy = k.fy * zoom,
            cx = (k.cx - nativeFrame.width / 2.0) * zoom + nativeFrame.width / 2.0,
            cy = (k.cy - nativeFrame.height / 2.0) * zoom + nativeFrame.height / 2.0,
            skew = k.skew * zoom,
        )

        val crop = input.cropRegion ?: PixelRect(0, 0, nativeFrame.width, nativeFrame.height)
        val outAspect = input.outputWidth.toDouble() / input.outputHeight
        val cropAspect = crop.width.toDouble() / crop.height
        val effW: Double
        val effH: Double
        if (cropAspect > outAspect) {
            effH = crop.height.toDouble()
            effW = effH * outAspect
        } else {
            effW = crop.width.toDouble()
            effH = effW / outAspect
        }
        val effLeft = crop.left + (crop.width - effW) / 2.0
        val effTop = crop.top + (crop.height - effH) / 2.0
        val sx = input.outputWidth / effW
        val sy = input.outputHeight / effH

        if (abs(sx - sy) / sx > MAX_ANISOTROPIC_SCALE) {
            warnings += "Non-uniform scale (sx=$sx, sy=$sy) between sensor crop and output."
        }

        val cx = (zoomed.cx - effLeft) * sx
        val cy = (zoomed.cy - effTop) * sy
        if (cx !in 0.0..input.outputWidth.toDouble() || cy !in 0.0..input.outputHeight.toDouble()) {
            warnings += "Principal point ($cx, $cy) lies outside the image; calibration may be wrong."
        }
        if (input.distortionCorrectionApplied && input.distortion != null) {
            warnings += "Lens distortion was corrected on-device; coefficients describe the raw " +
                "lens, not the saved image."
        }

        return IntrinsicsOutcome.Available(
            FrameIntrinsics(
                source = source,
                fx = zoomed.fx * sx,
                fy = zoomed.fy * sy,
                cx = cx,
                cy = cy,
                skew = zoomed.skew * sx,
                width = input.outputWidth,
                height = input.outputHeight,
                distortion = input.distortion,
                distortionAppliesToImage = input.distortion != null && !input.distortionCorrectionApplied,
                mapping = OutputMapping(
                    coordinateSystem = input.coordinateSystem,
                    nativeWidth = nativeFrame.width,
                    nativeHeight = nativeFrame.height,
                    cropRegion = crop,
                    effectiveCropLeft = effLeft,
                    effectiveCropTop = effTop,
                    effectiveCropWidth = effW,
                    effectiveCropHeight = effH,
                    scaleX = sx,
                    scaleY = sy,
                    zoomRatio = input.zoomRatio,
                ),
                warnings = warnings,
            )
        )
    }

    private fun nativeIntrinsics(
        input: IntrinsicsInput,
        nativeFrame: PixelRect,
    ): Pair<IntrinsicsSource, LensCalibration>? {
        val sensor = input.sensor
        input.reportedCalibration?.let { k ->
            // Per LENS_INTRINSIC_CALIBRATION docs, processed outputs are shifted into the
            // active-array origin after correction; a no-op when the native frame is pre-correction.
            val dx = nativeFrame.left - sensor.preCorrectionActiveArray.left
            val dy = nativeFrame.top - sensor.preCorrectionActiveArray.top
            val source = if (input.reportedCalibrationIsPerFrame) {
                IntrinsicsSource.REPORTED_PER_FRAME
            } else {
                IntrinsicsSource.REPORTED_STATIC
            }
            return source to k.copy(cx = k.cx - dx, cy = k.cy - dy)
        }

        input.profileCalibration?.let { k ->
            // Measured on the processed stream, so already relative to the active array.
            val dx = nativeFrame.left - sensor.activeArray.left
            val dy = nativeFrame.top - sensor.activeArray.top
            return IntrinsicsSource.CALIBRATED_PROFILE to k.copy(cx = k.cx - dx, cy = k.cy - dy)
        }

        val focalMm = input.focalLengthMm?.takeIf { it > 0f } ?: return null
        val physW = sensor.physicalWidthMm?.takeIf { it > 0f } ?: return null
        val physH = sensor.physicalHeightMm?.takeIf { it > 0f } ?: return null
        // Pixels are square. When the per-axis pitches disagree, the pixel array is a crop of the
        // physical sensor (e.g. 16:9 read out at full width from a 4:3 sensor), and the smaller pitch
        // is the axis read out in full.
        val f = focalMm.toDouble() / pixelPitchMm(sensor, physW, physH)
        return IntrinsicsSource.ESTIMATED_FROM_FOCAL_LENGTH to LensCalibration(
            fx = f,
            fy = f,
            cx = nativeFrame.width / 2.0,
            cy = nativeFrame.height / 2.0,
            skew = 0.0,
        )
    }

    private fun pixelPitchMm(sensor: SensorGeometry, physW: Float, physH: Float): Double =
        minOf(physW.toDouble() / sensor.pixelArrayWidth, physH.toDouble() / sensor.pixelArrayHeight)

    private fun hasNonSquarePitch(sensor: SensorGeometry): Boolean {
        val physW = sensor.physicalWidthMm?.takeIf { it > 0f } ?: return false
        val physH = sensor.physicalHeightMm?.takeIf { it > 0f } ?: return false
        val pitchX = physW.toDouble() / sensor.pixelArrayWidth
        val pitchY = physH.toDouble() / sensor.pixelArrayHeight
        return kotlin.math.abs(pitchX - pitchY) / minOf(pitchX, pitchY) > MAX_ANISOTROPIC_SCALE
    }
}
