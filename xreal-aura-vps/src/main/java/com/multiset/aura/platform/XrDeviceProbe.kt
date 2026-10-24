/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.platform

import android.content.Context
import android.os.Build
import androidx.xr.runtime.DisplayBlendMode
import androidx.xr.runtime.XrDevice

data class XrDeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val hasXrSpatialApi: Boolean,
    val hasOpenXrApi: Boolean,
    val displayBlendMode: String?,
    val notes: List<String>,
) {
    val isAndroidXr: Boolean get() = hasXrSpatialApi || hasOpenXrApi
    val isOpticalSeeThrough: Boolean get() = displayBlendMode == DisplayBlendMode.ADDITIVE.toString()

    val summary: String
        get() = buildString {
            append("$manufacturer $model · Android API $sdkInt · ")
            append(if (isAndroidXr) "Android XR" else "not an Android XR device")
            displayBlendMode?.let { append(" · display $it") }
        }
}

/** Identifies the XR runtime and display type so capture results can be interpreted correctly. */
object XrDeviceProbe {

    // Literal values of PackageManager.FEATURE_XR_API_*; the constants are @FlaggedApi.
    private const val FEATURE_XR_API_SPATIAL = "android.software.xr.api.spatial"
    private const val FEATURE_XR_API_OPENXR = "android.software.xr.api.openxr"

    /** [activityContext] must be the Activity, since the XR runtime binds to its first context. */
    fun probe(activityContext: Context): XrDeviceInfo {
        val pm = activityContext.packageManager
        val hasSpatial = pm.hasSystemFeature(FEATURE_XR_API_SPATIAL)
        val hasOpenXr = pm.hasSystemFeature(FEATURE_XR_API_OPENXR)
        val notes = mutableListOf<String>()

        var blendMode: String? = null
        if (hasSpatial || hasOpenXr) {
            runCatching { XrDevice.getCurrentDevice(activityContext).getPreferredDisplayBlendMode() }
                .onSuccess { mode ->
                    blendMode = mode.toString()
                    notes += when (mode) {
                        DisplayBlendMode.ADDITIVE ->
                            "Optical see-through display (e.g. XREAL Aura): there is no passthrough " +
                                "video. Frames come from the glasses' world-facing camera, whose " +
                                "field of view and pose differ from what the user sees."
                        DisplayBlendMode.ALPHA_BLEND ->
                            "Video-passthrough headset (e.g. Galaxy XR): camera 0 is the " +
                                "world-facing passthrough stream."
                        else -> "Runtime reports no display blend mode."
                    }
                }
                .onFailure { notes += "Could not query the XR display blend mode: ${it.message}" }
        } else {
            notes += "No Android XR system feature found. This sample needs an Android XR device such as " +
                "XREAL Aura; use the :app sample on phones."
        }

        return XrDeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            hasXrSpatialApi = hasSpatial,
            hasOpenXrApi = hasOpenXr,
            displayBlendMode = blendMode,
            notes = notes,
        )
    }
}
