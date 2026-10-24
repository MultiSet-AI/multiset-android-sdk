/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure

/** A capture failure whose [message] is written to be shown to the user as-is. */
class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    companion object {
        fun permissionMissing() = CaptureException(
            "Camera permission is not granted. Grant it to capture frames from the glasses camera."
        )

        fun noCameras() = CaptureException(
            "This device exposes no cameras to apps through Camera2. On XREAL Aura this means the " +
                "glasses camera is not available to third-party apps on this system build."
        )

        fun noWorldFacingCamera(inventory: List<CameraDescriptor>) = CaptureException(
            "No world-facing camera is exposed to apps. Available: " +
                inventory.joinToString { "id=${it.id} (${it.facingLabel})" } +
                ". User-facing cameras on Android XR return an avatar stream, not the world, so " +
                "they are not used."
        )

        fun unsupportedFormats(cameraId: String) = CaptureException(
            "Camera $cameraId offers neither JPEG nor YUV_420_888 output, so a still frame " +
                "cannot be read by the app."
        )

        fun timeout(stage: String) = CaptureException("Timed out while $stage. Try again.")

        fun captureFailed(reason: Int) = CaptureException(
            "The camera dropped the still frame (" +
                (if (reason == CaptureFailure.REASON_FLUSHED) "flushed" else "error") + "). Try again."
        )

        fun storageFailed(cause: Throwable) = CaptureException(
            "Could not save the capture to app storage: ${cause.message}", cause
        )

        fun fromDeviceError(error: Int) = CaptureException(
            when (error) {
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                    "The camera is in use by another app or by the system."
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                    "The camera is disabled by device policy or by the system."
                CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                    "The camera service failed. Restart the app or device."
                else -> "The camera device reported a fatal error (code $error)."
            }
        )

        fun from(throwable: Throwable): CaptureException = when (throwable) {
            is CaptureException -> throwable
            is SecurityException -> CaptureException(
                "Camera access was refused by the system: ${throwable.message}", throwable
            )
            is CameraAccessException -> CaptureException(
                when (throwable.reason) {
                    CameraAccessException.CAMERA_DISABLED ->
                        "The camera is disabled by device policy or by the system."
                    CameraAccessException.CAMERA_DISCONNECTED ->
                        "The camera disconnected (the app may have lost focus, or the glasses " +
                            "were unplugged)."
                    CameraAccessException.CAMERA_IN_USE,
                    CameraAccessException.MAX_CAMERAS_IN_USE ->
                        "The camera is in use by another app or by the system."
                    else -> "Camera error: ${throwable.message}"
                },
                throwable,
            )
            is IllegalArgumentException -> CaptureException(
                "The camera rejected the capture configuration: ${throwable.message}", throwable
            )
            is IllegalStateException -> CaptureException(
                "The camera closed during capture: ${throwable.message}", throwable
            )
            else -> CaptureException("Capture failed: ${throwable.message ?: throwable}", throwable)
        }
    }
}
