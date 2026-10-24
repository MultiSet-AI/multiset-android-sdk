/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import kotlin.math.max

data class PixelSize(val width: Int, val height: Int)

/**
 * The preview stream size: the largest size within the bound with the sensor's own aspect (the
 * uncropped still's, so the preview frames what is sent for localization), else the largest within
 * it, else the smallest available. The default bound is 1080p, the largest preview Camera2
 * guarantees beside a maximum-size JPEG.
 */
fun choosePreviewSize(
    sizes: List<PixelSize>,
    sensorAspect: PixelSize?,
    maxLong: Int = 1920,
    maxShort: Int = 1080,
): PixelSize? {
    val area = compareBy<PixelSize> { it.width.toLong() * it.height }
    val inBounds = sizes.filter { max(it.width, it.height) <= maxLong && minOf(it.width, it.height) <= maxShort }
    val sameAspect = sensorAspect?.let { aspect -> inBounds.filter { it.hasAspectOf(aspect) } }.orEmpty()
    return sameAspect.maxWithOrNull(area) ?: inBounds.maxWithOrNull(area) ?: sizes.minWithOrNull(area)
}

private fun PixelSize.hasAspectOf(other: PixelSize): Boolean =
    max(width, height).toLong() * minOf(other.width, other.height) ==
        minOf(width, height).toLong() * max(other.width, other.height)

/**
 * Row-major 3x3 affine for `TextureView.setTransform`. TextureView first stretches the [buffer] (as
 * it shows it) over the view; this turns it [rotationCw] degrees clockwise and scales it uniformly to
 * cover the view, centred, cropping whatever overhangs.
 */
fun previewTransform(buffer: PixelSize, rotationCw: Int, viewWidth: Float, viewHeight: Float): FloatArray {
    val bw = buffer.width.toFloat()
    val bh = buffer.height.toFloat()
    val quarterTurn = rotationCw % 180 != 0
    val rotatedWidth = if (quarterTurn) bh else bw
    val rotatedHeight = if (quarterTurn) bw else bh
    val scale = max(viewWidth / rotatedWidth, viewHeight / rotatedHeight)

    // Buffer pixel (x, y) to its place in the upright image, as rows (a, b, c) and (d, e, f).
    val rotation = when (rotationCw) {
        90 -> floatArrayOf(0f, -1f, bh, 1f, 0f, 0f)
        180 -> floatArrayOf(-1f, 0f, bw, 0f, -1f, bh)
        270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, bw)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    }
    // Upright image to view: scale about its centre, then centre in the view.
    val tx = viewWidth / 2 - scale * rotatedWidth / 2
    val ty = viewHeight / 2 - scale * rotatedHeight / 2
    // Undo TextureView's stretch: view (sx, sy) came from buffer (sx * bw / vw, sy * bh / vh).
    val ux = bw / viewWidth
    val uy = bh / viewHeight
    return floatArrayOf(
        scale * rotation[0] * ux, scale * rotation[1] * uy, scale * rotation[2] + tx,
        scale * rotation[3] * ux, scale * rotation[4] * uy, scale * rotation[5] + ty,
        0f, 0f, 1f,
    )
}

/**
 * [previewTransform] for a camera stream. Camera2 tags preview buffers with SENSOR_ORIENTATION, so
 * TextureView already shows them in the display's natural orientation; only the display rotation is
 * left to undo.
 */
fun cameraPreviewTransform(
    buffer: PixelSize,
    sensorOrientation: Int,
    displayRotation: Int,
    viewWidth: Float,
    viewHeight: Float,
): FloatArray {
    val natural = if (sensorOrientation % 180 == 0) buffer else PixelSize(buffer.height, buffer.width)
    return previewTransform(natural, (360 - displayRotation) % 360, viewWidth, viewHeight)
}
