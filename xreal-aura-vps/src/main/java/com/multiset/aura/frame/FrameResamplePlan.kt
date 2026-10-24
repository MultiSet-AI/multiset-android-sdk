/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.frame

import com.multiset.aura.intrinsics.FrameIntrinsics
import kotlin.math.roundToInt

/**
 * Scale -> clockwise rotation, applied in that order to the whole captured frame: nothing is
 * cropped. Every step maps image edges to image edges, so a point maps exactly as [mapPoint] describes.
 */
data class FrameResamplePlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val rotationDegrees: Int,
) {
    init {
        require(rotationDegrees in QUARTER_TURNS) { "Rotation must be one of $QUARTER_TURNS" }
    }

    val scaleX: Double get() = scaledWidth.toDouble() / sourceWidth
    val scaleY: Double get() = scaledHeight.toDouble() / sourceHeight
    val isUpscaled: Boolean get() = scaleX > 1.0 || scaleY > 1.0
    val outputWidth: Int get() = if (rotationDegrees % 180 == 0) scaledWidth else scaledHeight
    val outputHeight: Int get() = if (rotationDegrees % 180 == 0) scaledHeight else scaledWidth

    /** Maps a continuous source-image coordinate (pixel centers at +0.5) to the output image. */
    fun mapPoint(u: Double, v: Double): Pair<Double, Double> {
        val su = u * scaleX
        val sv = v * scaleY
        return when (rotationDegrees) {
            90 -> (scaledHeight - sv) to su
            180 -> (scaledWidth - su) to (scaledHeight - sv)
            270 -> sv to (scaledWidth - su)
            else -> su to sv
        }
    }

    /** Re-expresses intrinsics of the source frame in pixels of the output frame. */
    fun transform(k: FrameIntrinsics): FrameIntrinsics {
        require(k.width == sourceWidth && k.height == sourceHeight) {
            "Intrinsics are for ${k.width}x${k.height}, plan expects ${sourceWidth}x$sourceHeight"
        }
        val fx = k.fx * scaleX
        val fy = k.fy * scaleY
        val skew = k.skew * scaleX
        val (cx, cy) = mapPoint(k.cx, k.cy)
        val warnings = k.warnings.toMutableList()
        if (isUpscaled) {
            warnings += "Output was upscaled from a ${sourceWidth}x$sourceHeight frame; it has no extra detail."
        }
        if (rotationDegrees % 180 != 0 && skew != 0.0) {
            warnings += "Non-zero skew ($skew) cannot be represented after a 90-degree rotation; dropped."
        }
        // Normalized coords rotate too: 90 CW maps (x, y) to (-y, x), which permutes p1/p2.
        val distortion = k.distortion?.let { d ->
            when (rotationDegrees) {
                90 -> d.copy(p1 = d.p2, p2 = -d.p1)
                180 -> d.copy(p1 = -d.p1, p2 = -d.p2)
                270 -> d.copy(p1 = -d.p2, p2 = d.p1)
                else -> d
            }
        }
        val quarterTurn = rotationDegrees % 180 != 0
        return k.copy(
            fx = if (quarterTurn) fy else fx,
            fy = if (quarterTurn) fx else fy,
            cx = cx,
            cy = cy,
            skew = if (quarterTurn) 0.0 else skew,
            width = outputWidth,
            height = outputHeight,
            distortion = distortion,
            warnings = warnings,
        )
    }

    companion object {
        private val QUARTER_TURNS = setOf(0, 90, 180, 270)

        /**
         * Scales the whole source uniformly, up or down, so its long side is [longSide], then rotates
         * it by [rotationDegrees] clockwise. The MultiSet SDK sizes a frame the same way, so a plan
         * built with the SDK's long side reaches it at scale 1.0.
         */
        fun create(sourceWidth: Int, sourceHeight: Int, longSide: Int, rotationDegrees: Int): FrameResamplePlan {
            require(sourceWidth > 0 && sourceHeight > 0 && longSide > 0) { "Sizes must be positive" }
            val scale = longSide.toDouble() / maxOf(sourceWidth, sourceHeight)
            return FrameResamplePlan(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                scaledWidth = maxOf(1, (sourceWidth * scale).roundToInt()),
                scaledHeight = maxOf(1, (sourceHeight * scale).roundToInt()),
                rotationDegrees = rotationDegrees,
            )
        }

        /**
         * Clockwise rotation that makes a world-facing sensor frame upright for the current display
         * rotation (Camera2's JPEG_ORIENTATION rule, with display rotation for device orientation).
         */
        fun uprightRotation(sensorOrientation: Int, displayRotationDegrees: Int): Int =
            (sensorOrientation - displayRotationDegrees + 360) % 360
    }
}
