/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.multiset.aura.platform.displayRotationDegrees
import com.multiset.aura.platform.findActivity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * The world camera's live feed, upright and centre-cropped to fill [modifier]'s bounds. It holds the
 * camera only while it is on screen, the activity is resumed and [cameraGranted] is true.
 */
@Composable
fun CameraPreview(camera: CameraHub, cameraGranted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val bufferSize = remember(camera) { camera.previewSize() }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    fun SurfaceTexture.useStreamSize() {
        bufferSize?.let { setDefaultBufferSize(it.width, it.height) }
    }

    fun TextureView.fit() {
        val size = bufferSize ?: return
        if (width == 0 || height == 0) return
        val values = cameraPreviewTransform(
            size, camera.sensorOrientation(), activity.displayRotationDegrees(), width.toFloat(), height.toFloat(),
        )
        setTransform(Matrix().apply { setValues(values) })
    }

    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        surface.useStreamSize()
                        fit()
                        texture = surface
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        // TextureView resets the buffer to the view's size on every resize.
                        surface.useStreamSize()
                        fit()
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        texture = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        update = { it.fit() },
        modifier = modifier,
    )

    // Captured here, not read inside the effect: a late-starting run must see the value it was keyed on.
    val target = texture?.takeIf { resumed && cameraGranted }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        target.useStreamSize()
        val surface = Surface(target)
        try {
            camera.attachPreview(surface)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { camera.detachPreview(surface) }
            surface.release()
        }
    }
}
