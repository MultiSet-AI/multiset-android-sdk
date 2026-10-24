/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size

/** What Camera2 reports about one camera. */
data class CameraDescriptor(
    val id: String,
    val facing: Int?,
    val hardwareLevel: Int?,
    val hasIntrinsicCalibration: Boolean,
    val hasLensDistortion: Boolean,
    val largestJpeg: Size?,
    val largestYuv: Size?,
) {
    val facingLabel: String
        get() = when (facing) {
            CameraMetadata.LENS_FACING_BACK -> "world-facing"
            CameraMetadata.LENS_FACING_FRONT -> "user-facing"
            CameraMetadata.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown facing"
        }

    val hardwareLevelLabel: String
        get() = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
}

class WorldCameraSelector(private val cameraManager: CameraManager) {

    fun inventory(): List<CameraDescriptor> = cameraManager.cameraIdList.map { id ->
        val ch = cameraManager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        CameraDescriptor(
            id = id,
            facing = ch.get(CameraCharacteristics.LENS_FACING),
            hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            hasIntrinsicCalibration = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null,
            hasLensDistortion = ch.get(CameraCharacteristics.LENS_DISTORTION) != null,
            largestJpeg = map?.getOutputSizes(ImageFormat.JPEG).largest(),
            largestYuv = map?.getOutputSizes(ImageFormat.YUV_420_888).largest(),
        )
    }

    /**
     * Android XR maps the world-facing stream to LENS_FACING_BACK (camera "0"). Glasses tethered
     * to a host may surface their camera as EXTERNAL instead, so that is the fallback. FRONT is
     * never chosen: on XR headsets it carries the user's avatar, not the scene.
     */
    fun select(inventory: List<CameraDescriptor>): CameraDescriptor {
        if (inventory.isEmpty()) throw CaptureException.noCameras()
        val byPreference = compareBy<CameraDescriptor>({ it.id.toIntOrNull() ?: Int.MAX_VALUE }, { it.id })
        return inventory.filter { it.facing == CameraMetadata.LENS_FACING_BACK }.minWithOrNull(byPreference)
            ?: inventory.filter { it.facing == CameraMetadata.LENS_FACING_EXTERNAL }.minWithOrNull(byPreference)
            ?: throw CaptureException.noWorldFacingCamera(inventory)
    }

    private fun Array<Size>?.largest(): Size? = this?.maxByOrNull { it.width.toLong() * it.height }
}
