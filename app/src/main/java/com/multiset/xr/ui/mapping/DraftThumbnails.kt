/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.mapping

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decodes a draft's first frame at roughly the size its row shows it. Call [decode] off the main thread. */
object DraftThumbnails {

    /** The largest power of two that still leaves the image at least [targetWidth] wide. */
    fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    /** The image at [path] subsampled toward [targetWidth] pixels, or null when it cannot be read. */
    fun decode(path: String, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth) }
        return BitmapFactory.decodeFile(path, options)
    }
}
