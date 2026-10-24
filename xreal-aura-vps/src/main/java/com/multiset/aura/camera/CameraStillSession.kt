/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class ExposureWarmup { CONVERGED, NOT_REPORTED, TIMED_OUT, SKIPPED }

/** A surface showing the live camera feed; its buffer size is set by whoever owns it. */
class PreviewTarget(val surface: Surface)

class CapturedFrame(
    val cameraId: String,
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val streamFormat: String,
    val result: TotalCaptureResult,
    val exposureWarmup: ExposureWarmup,
    val imageTimestampMatchesResult: Boolean,
    val resultReceivedAtEpochMs: Long,
    val resultReceivedAtElapsedNs: Long,
)

/**
 * An open Camera2 session for still frames. Opening warms up auto-exposure once; [takeStill] then
 * returns a frame per call until [close]. Geometry controls are pinned so intrinsics stay derivable.
 */
class CameraStillSession private constructor(
    private val cameraId: String,
    private val streams: StreamChoice,
    private val controls: GeometryControls,
    private val resources: Resources,
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val exposureWarmup: ExposureWarmup,
) : AutoCloseable {

    @Volatile
    private var closed = false

    /** False once closed or once the camera reported a disconnect or error. */
    val isUsable: Boolean get() = !closed && resources.deviceLoss.error == null

    suspend fun takeStill(): CapturedFrame {
        check(!closed) { "Camera session is closed" }
        var stage = "capturing the still frame"
        try {
            val result = withTimeout(CAPTURE_TIMEOUT_MS) {
                captureStill(session, device, resources.stillReader.surface, controls, resources.handler)
            }
            val receivedEpochMs = System.currentTimeMillis()
            val receivedElapsedNs = SystemClock.elapsedRealtimeNanos()
            val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)

            stage = "receiving the image"
            // Only stills reach a JPEG reader, so any image is ours; YUV also carries warm-up frames.
            val matchTimestamp = sensorTimestamp.takeIf { streams.stillFormat == ImageFormat.YUV_420_888 }
            val image = withTimeout(IMAGE_TIMEOUT_MS) { resources.stillImages.await(matchTimestamp) }
            val timestampMatches = image.timestamp == sensorTimestamp
            val jpeg = image.use { encodeJpeg(it) }

            return CapturedFrame(
                cameraId = cameraId,
                jpegBytes = jpeg,
                width = streams.still.width,
                height = streams.still.height,
                streamFormat = if (streams.stillFormat == ImageFormat.JPEG) "JPEG" else "YUV_420_888",
                result = result,
                exposureWarmup = exposureWarmup,
                imageTimestampMatchesResult = timestampMatches,
                resultReceivedAtEpochMs = receivedEpochMs,
                resultReceivedAtElapsedNs = receivedElapsedNs,
            )
        } catch (e: Throwable) {
            // A failed still's image may still arrive and would be paired with the next still's result.
            close()
            throw when (e) {
                is TimeoutCancellationException -> resources.deviceLoss.error ?: CaptureException.timeout(stage)
                is CancellationException -> e
                else -> resources.deviceLoss.error ?: CaptureException.from(e)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
        runCatching { device.close() }
        resources.close()
    }

    private class StreamChoice(val stillFormat: Int, val still: Size, val warmup: Size?)

    /** Pins every control that changes the image-to-sensor mapping, so intrinsics stay derivable. */
    private class GeometryControls(ch: CameraCharacteristics) {
        private val zoomRatio = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ?.takeIf { it.contains(1f) }?.let { 1f }
        private val distortionMode = ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.let {
            when {
                CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY in it ->
                    CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                CameraMetadata.DISTORTION_CORRECTION_MODE_FAST in it ->
                    CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
                else -> null
            }
        }
        private val rotateAndCrop = ch.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
            ?.takeIf { CameraMetadata.SCALER_ROTATE_AND_CROP_NONE in it }
            ?.let { CameraMetadata.SCALER_ROTATE_AND_CROP_NONE }

        fun applyTo(builder: CaptureRequest.Builder) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            zoomRatio?.let { builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
            distortionMode?.let { builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
            rotateAndCrop?.let { builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP, it) }
        }
    }

    private class DeviceLossMonitor {
        @Volatile
        var error: CaptureException? = null
    }

    /** Everything that exists before the camera device does, released together. */
    private class Resources(streams: StreamChoice) : AutoCloseable {
        val thread = HandlerThread("AuraCamera").apply { start() }
        val handler = Handler(thread.looper)
        val stillReader: ImageReader = ImageReader.newInstance(
            streams.still.width, streams.still.height, streams.stillFormat, STILL_READER_DEPTH
        )
        val warmupReader: ImageReader? = streams.warmup?.let {
            ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 2)
        }
        val stillImages = TimestampedBuffer<Image>(capacity = STILL_READER_DEPTH - 1)
        val deviceLoss = DeviceLossMonitor()

        init {
            stillReader.setOnImageAvailableListener({ reader ->
                runCatching { reader.acquireNextImage() }.getOrNull()?.let { stillImages.offer(it.timestamp, it) }
            }, handler)
            warmupReader?.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        }

        override fun close() {
            stillImages.close()
            stillReader.close()
            warmupReader?.close()
            thread.quitSafely()
        }
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 5_000L
        private const val SESSION_TIMEOUT_MS = 5_000L
        private const val WARMUP_TIMEOUT_MS = 2_500L
        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private const val IMAGE_TIMEOUT_MS = 5_000L
        private const val MIN_WARMUP_FRAMES = 5
        private const val WARMUP_MIN_AREA = 640 * 480
        private const val STILL_READER_DEPTH = 3
        private const val JPEG_QUALITY = 95
        private val AE_SETTLED_STATES = setOf(
            CameraMetadata.CONTROL_AE_STATE_CONVERGED,
            CameraMetadata.CONTROL_AE_STATE_LOCKED,
            CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED,
        )

        /**
         * Opens [cameraId] and settles auto-exposure; the caller must [close] the session. With a
         * [preview], its repeating request keeps running between stills and drives auto-exposure.
         */
        suspend fun open(
            context: Context,
            cameraManager: CameraManager,
            cameraId: String,
            characteristics: CameraCharacteristics,
            preview: PreviewTarget? = null,
        ): CameraStillSession {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw CaptureException.permissionMissing()
            }
            val streams = chooseStreams(cameraId, characteristics, needsWarmupStream = preview == null)
            val controls = GeometryControls(characteristics)
            val resources = Resources(streams)
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var stage = "opening the camera"
            try {
                device = withTimeout(OPEN_TIMEOUT_MS) {
                    openCamera(cameraManager, cameraId, resources.handler, resources.deviceLoss)
                }

                stage = "configuring camera streams"
                val outputs = listOfNotNull(preview?.surface, resources.warmupReader?.surface, resources.stillReader.surface)
                session = withTimeout(SESSION_TIMEOUT_MS) { createSession(device, outputs, resources.handler) }

                stage = "waiting for auto-exposure"
                val warmupReader = resources.warmupReader
                val warmup = when {
                    preview != null -> warmUp(session, device, preview.surface, controls, resources.handler)
                    warmupReader != null -> warmUp(session, device, warmupReader.surface, controls, resources.handler)
                    streams.stillFormat == ImageFormat.YUV_420_888 ->
                        // The warm-up shares the still stream here, so it must stop before stills.
                        warmUp(session, device, resources.stillReader.surface, controls, resources.handler)
                            .also { session.stopRepeating() }
                    else -> ExposureWarmup.SKIPPED
                }
                return CameraStillSession(cameraId, streams, controls, resources, device, session, warmup)
            } catch (e: Throwable) {
                runCatching { session?.close() }
                runCatching { device?.close() }
                resources.close()
                throw when (e) {
                    is TimeoutCancellationException -> resources.deviceLoss.error ?: CaptureException.timeout(stage)
                    is CancellationException -> e
                    else -> resources.deviceLoss.error ?: CaptureException.from(e)
                }
            }
        }

        private fun chooseStreams(cameraId: String, ch: CameraCharacteristics, needsWarmupStream: Boolean): StreamChoice {
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw CaptureException.unsupportedFormats(cameraId)
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val largest = compareBy<Size> { it.width.toLong() * it.height }
            return when {
                jpegSizes.isNotEmpty() -> StreamChoice(
                    stillFormat = ImageFormat.JPEG,
                    still = jpegSizes.maxWith(largest),
                    warmup = if (!needsWarmupStream) {
                        null
                    } else {
                        yuvSizes.filter { it.width * it.height >= WARMUP_MIN_AREA }.minWithOrNull(largest)
                            ?: yuvSizes.maxWithOrNull(largest)
                    },
                )
                yuvSizes.isNotEmpty() -> StreamChoice(ImageFormat.YUV_420_888, yuvSizes.maxWith(largest), null)
                else -> throw CaptureException.unsupportedFormats(cameraId)
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun openCamera(
            cameraManager: CameraManager,
            cameraId: String,
            handler: Handler,
            deviceLoss: DeviceLossMonitor,
        ): CameraDevice = suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    val error = CaptureException.from(
                        CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED)
                    )
                    deviceLoss.error = error
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(error)
                }

                override fun onError(camera: CameraDevice, errorCode: Int) {
                    val error = CaptureException.fromDeviceError(errorCode)
                    deviceLoss.error = error
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(error)
                }
            }, handler)
        }

        private suspend fun createSession(
            device: CameraDevice,
            surfaces: List<Surface>,
            handler: Handler,
        ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            CaptureException("The camera rejected the ${surfaces.size}-stream configuration.")
                        )
                    }
                }
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map(::OutputConfiguration),
                Executor { handler.post(it) },
                callback,
            )
            device.createCaptureSession(config)
        }

        private suspend fun warmUp(
            session: CameraCaptureSession,
            device: CameraDevice,
            target: Surface,
            controls: GeometryControls,
            handler: Handler,
        ): ExposureWarmup {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(target)
                controls.applyTo(this)
            }.build()
            val settled = CompletableDeferred<ExposureWarmup>()
            var frames = 0
            session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    frames++
                    if (frames < MIN_WARMUP_FRAMES) return
                    when (result.get(CaptureResult.CONTROL_AE_STATE)) {
                        null -> settled.complete(ExposureWarmup.NOT_REPORTED)
                        in AE_SETTLED_STATES -> settled.complete(ExposureWarmup.CONVERGED)
                    }
                }
            }, handler)
            return withTimeoutOrNull(WARMUP_TIMEOUT_MS) { settled.await() } ?: ExposureWarmup.TIMED_OUT
        }

        private suspend fun captureStill(
            session: CameraCaptureSession,
            device: CameraDevice,
            target: Surface,
            controls: GeometryControls,
            handler: Handler,
        ): TotalCaptureResult = suspendCancellableCoroutine { cont ->
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(target)
                controls.applyTo(this)
                // 0 keeps pixels in sensor orientation so intrinsics need no rotation.
                set(CaptureRequest.JPEG_ORIENTATION, 0)
                set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
            }.build()
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    if (cont.isActive) cont.resumeWithException(CaptureException.captureFailed(failure.reason))
                }
            }, handler)
        }

        private fun encodeJpeg(image: Image): ByteArray = when (image.format) {
            ImageFormat.JPEG -> image.planes[0].buffer.let { buffer ->
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
            ImageFormat.YUV_420_888 -> ByteArrayOutputStream().use { out ->
                YuvImage(image.toNv21(), ImageFormat.NV21, image.width, image.height, null)
                    .compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
                out.toByteArray()
            }
            else -> error("Unexpected image format ${image.format}")
        }

        private fun Image.toNv21(): ByteArray {
            val (yPlane, uPlane, vPlane) = planes
            val nv21 = ByteArray(width * height * 3 / 2)
            val yBuffer = yPlane.buffer
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    nv21[pos++] = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                    nv21[pos++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                }
            }
            return nv21
        }
    }
}
