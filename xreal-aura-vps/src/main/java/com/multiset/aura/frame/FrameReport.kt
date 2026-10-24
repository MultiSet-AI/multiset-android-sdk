/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.frame

import com.multiset.aura.intrinsics.IntrinsicsSource
import com.multiset.sdk.model.DeviceOrientation
import java.util.Locale

/** The frame last handed to the SDK, as the intrinsics and orientation it was sent with. */
data class FrameReport(
    val width: Int,
    val height: Int,
    val orientation: DeviceOrientation,
    val intrinsicsSource: IntrinsicsSource,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val sensorOrientation: Int,
    val captureMillis: Long,
) {
    val summary: String
        get() = "${width}x$height $orientation · $intrinsicsSource fx ${"%.1f".format(Locale.ROOT, fx)} · " +
            "sensor $sensorOrientation° · $captureMillis ms"
}
