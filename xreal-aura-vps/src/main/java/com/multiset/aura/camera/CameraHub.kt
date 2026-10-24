/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HubCamera(val id: String, val characteristics: CameraCharacteristics)

class Still(val frame: CapturedFrame, val camera: HubCamera)

/**
 * The only owner of the world camera: Camera2 allows one client, and the preview and localization
 * both need it. With a preview attached the session stays open and shows the live feed;
 * without one it opens for a still and closes after a quiet period.
 */
class CameraHub(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val scope: CoroutineScope,
) {

    private class OpenSession(val session: CameraStillSession, val camera: HubCamera, val withPreview: Boolean)

    private val selector = WorldCameraSelector(cameraManager)
    private val mutex = Mutex()
    private var open: OpenSession? = null
    private val preview = PreviewSlot<Surface>()
    private val idleCloser = IdleCloser(scope, IDLE_CLOSE_MS) {
        mutex.withLock { withContext(CAMERA_CONTEXT) { if (preview.current == null) closeSession() } }
    }

    private val _status = MutableStateFlow<CameraStatus>(CameraStatus.Off)

    /** Live while the preview is showing frames. */
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    @Volatile
    private var selected: HubCamera? = null

    /** Retried until it succeeds: a camera can enumerate late, for example after the glasses reconnect. */
    private fun camera(): HubCamera = selected ?: selector.select(selector.inventory()).let { descriptor ->
        HubCamera(descriptor.id, cameraManager.getCameraCharacteristics(descriptor.id)).also { selected = it }
    }

    private fun cameraOrNull(): HubCamera? =
        runCatching { camera() }.onFailure { Log.w(TAG, "No world camera", it) }.getOrNull()

    fun previewSize(): PixelSize? {
        val characteristics = cameraOrNull()?.characteristics ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { PixelSize(it.width, it.height) }
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return choosePreviewSize(sizes, activeArray?.let { PixelSize(it.width(), it.height()) })
    }

    fun sensorOrientation(): Int = cameraOrNull()?.characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    suspend fun attachPreview(surface: Surface) = withContext(CAMERA_CONTEXT) {
        mutex.withLock {
            preview.attach(surface)
            idleCloser.cancel()
            try {
                reopen()
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the camera preview", e)
            }
        }
    }

    /** Stops the preview if [surface] is still the attached one; a replaced surface is ignored. */
    suspend fun detachPreview(surface: Surface) = withContext(CAMERA_CONTEXT) {
        mutex.withLock {
            if (!preview.detach(surface)) return@withLock
            closeSession()
            _status.value = CameraStatus.Off
        }
    }

    /** Takes one still, opening the camera if needed. Stills are serialized across all callers. */
    suspend fun takeStill(): Still = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val current = open?.takeIf { it.session.isUsable && it.withPreview == (preview.current != null) } ?: reopen()
                Still(current.session.takeStill(), current.camera)
            } catch (e: Exception) {
                // A failed or cancelled still closes its session; bring the preview back rather than leave it black.
                if (preview.current != null) scope.launch { restorePreview() }
                throw e
            } finally {
                if (preview.current == null) idleCloser.touch()
            }
        }
    }

    /** Closes a still-only session at once, rather than after the idle timeout. */
    suspend fun closeNow() = withContext(CAMERA_CONTEXT) {
        mutex.withLock {
            idleCloser.cancel()
            if (preview.current == null) closeSession()
        }
    }

    fun release() {
        idleCloser.cancel()
        if (mutex.tryLock()) {
            try {
                preview.clear()
                closeSession()
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun restorePreview() = withContext(CAMERA_CONTEXT) {
        mutex.withLock {
            if (preview.current != null && open?.session?.isUsable != true) runCatching { reopen() }
        }
    }

    private suspend fun reopen(): OpenSession {
        closeSession()
        val target = preview.current
        if (target != null) _status.value = CameraStatus.Opening
        try {
            val hubCamera = camera()
            val session = CameraStillSession.open(
                context, cameraManager, hubCamera.id, hubCamera.characteristics, target?.let(::PreviewTarget),
            )
            return OpenSession(session, hubCamera, withPreview = target != null).also {
                open = it
                if (target != null) _status.value = CameraStatus.Live
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = CameraStatus.Error(CaptureException.from(e).message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun closeSession() {
        open?.session?.close()
        open = null
    }

    private companion object {
        const val TAG = "CameraHub"
        const val IDLE_CLOSE_MS = 5_000L
        val CAMERA_CONTEXT = Dispatchers.Default + NonCancellable
    }
}
