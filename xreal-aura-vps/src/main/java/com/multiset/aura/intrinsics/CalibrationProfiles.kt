/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.intrinsics

/**
 * Intrinsics measured offline for a camera whose HAL reports none. [calibration] is in active-array
 * pixels with the Camera2 pixel-centre convention, like `LENS_INTRINSIC_CALIBRATION`.
 */
data class CalibrationProfile(
    val manufacturer: String,
    /** Every `Build.MODEL` the device has shipped under. */
    val models: Set<String>,
    val cameraId: String,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val calibration: LensCalibration,
    val provenance: String,
)

object CalibrationProfiles {

    /**
     * Aura camera 0. ChArUco video VID_20261007_161552 (k1, k2 model, no tangential terms), chosen
     * over the full model by an on-device VPS A/B test: median confidence 0.92 vs 0.905 and half the
     * fix-to-fix scatter. Distortion (k1 -0.040, k2 +0.021) is measured but not applied.
     */
    val AURA_CAMERA_0 = CalibrationProfile(
        manufacturer = "Google",
        // Aura's puck reports Build.MODEL "Android XR Puck".
        models = setOf("Aura", "Android XR Puck"),
        cameraId = "0",
        activeArrayWidth = 1920,
        activeArrayHeight = 1080,
        calibration = LensCalibration(fx = 1279.5, fy = 1279.5, cx = 963.0, cy = 530.6, skew = 0.0),
        provenance = "ChArUco calibration of Aura camera 0, 2026-10-07",
    )

    private val ALL = listOf(AURA_CAMERA_0)

    /** The profile for this camera, matched on device, camera id and active-array size. */
    fun find(manufacturer: String, model: String, cameraId: String, activeArray: PixelRect): CalibrationProfile? =
        ALL.firstOrNull {
            it.manufacturer.equals(manufacturer, ignoreCase = true) &&
                it.models.any { name -> name.equals(model, ignoreCase = true) } &&
                it.cameraId == cameraId &&
                it.activeArrayWidth == activeArray.width &&
                it.activeArrayHeight == activeArray.height
        }
}
