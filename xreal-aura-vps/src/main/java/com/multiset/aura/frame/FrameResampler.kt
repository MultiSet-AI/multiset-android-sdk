/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.frame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.multiset.aura.camera.CaptureException
import java.io.ByteArrayOutputStream

/** Executes a [FrameResamplePlan] on a JPEG. */
object FrameResampler {

    private const val JPEG_QUALITY = 95

    fun resample(jpeg: ByteArray, plan: FrameResamplePlan): ByteArray {
        val bitmap = resampleToBitmap(jpeg, plan)
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** The caller owns the returned bitmap. */
    fun resampleToBitmap(jpeg: ByteArray, plan: FrameResamplePlan): Bitmap {
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw CaptureException("The camera returned an image that could not be decoded.")
        if (bitmap.width != plan.sourceWidth || bitmap.height != plan.sourceHeight) {
            val decodedSize = "${bitmap.width}x${bitmap.height}"
            bitmap.recycle()
            throw CaptureException(
                "The camera returned a $decodedSize image for a " +
                    "${plan.sourceWidth}x${plan.sourceHeight} stream, so intrinsics cannot be mapped."
            )
        }
        // Bilinear at exactly 2:1 averages each 2x2 block, so halving first avoids aliasing that a
        // single large bilinear step would cause. Each step maps edges to edges, so geometry is exact.
        while (bitmap.width >= plan.scaledWidth * 2 && bitmap.height >= plan.scaledHeight * 2) {
            bitmap = bitmap.replaceWith(bitmap.scale(bitmap.width / 2, bitmap.height / 2))
        }
        if (bitmap.width != plan.scaledWidth || bitmap.height != plan.scaledHeight) {
            bitmap = bitmap.replaceWith(bitmap.scale(plan.scaledWidth, plan.scaledHeight))
        }
        if (plan.rotationDegrees != 0) {
            val rotation = Matrix().apply { postRotate(plan.rotationDegrees.toFloat()) }
            bitmap = bitmap.replaceWith(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotation, false))
        }
        return bitmap
    }

    private fun Bitmap.replaceWith(next: Bitmap): Bitmap {
        if (next !== this) recycle()
        return next
    }
}
