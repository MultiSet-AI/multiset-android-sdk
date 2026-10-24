/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import com.multiset.aura.localization.LocalizationEvent
import com.multiset.aura.localization.MapLocalizationViewModel
import com.multiset.aura.platform.XrDeviceViewModel
import com.multiset.aura.scene.glb.GridPalette
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Keeps the map space in the room in step with [viewModel]'s scene. Place it above the space-mode
 * switch so the map survives changing between Home Space and Full Space.
 */
@Composable
fun MapSceneEffect(viewModel: MapLocalizationViewModel, xr: XrDeviceViewModel) {
    val context = LocalContext.current
    val session = LocalSession.current
    val device by xr.device.collectAsStateWithLifecycle()
    val grants by xr.permissionGrants.collectAsStateWithLifecycle()
    val content3d = LocalSpatialCapabilities.current.isContent3dEnabled
    val isAndroidXr = device?.isAndroidXr == true
    val palette = if (device?.isOpticalSeeThrough == true) GridPalette.ADDITIVE else GridPalette.SAMPLE

    // The map is drawn over the real room, so Full Space must not dim it or swap in a virtual environment.
    DisposableEffect(session, isAndroidXr) {
        val scene = session?.takeIf { isAndroidXr }?.scene
        val logChange = Consumer<Float> { Log.i(TAG, "Passthrough opacity is now $it") }
        scene?.spatialEnvironment?.let { environment ->
            environment.preferredPassthroughOpacity = 1f
            Log.i(
                TAG,
                "Requested full passthrough: current opacity ${environment.currentPassthroughOpacity}, " +
                    "passthrough control ${SpatialCapability.PASSTHROUGH_CONTROL in scene.spatialCapabilities}"
            )
            environment.addPassthroughOpacityChangedListener(logChange)
        }
        onDispose { scene?.spatialEnvironment?.removePassthroughOpacityChangedListener(logChange) }
    }

    LaunchedEffect(session, isAndroidXr, palette, content3d, grants) {
        if (session == null || !isAndroidXr) return@LaunchedEffect
        // Once ticking stops the map is frozen, so nothing may report it shown over that failure.
        var halted = false
        val report: (LocalizationEvent) -> Unit = { if (!halted) viewModel.report(it) }
        val renderer = try {
            MapSpaceRenderer(context, session, palette, this, xr.headPose::currentPose, report)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the map scene", e)
            viewModel.report(LocalizationEvent.MeshFailed(viewModel.scene.value.epoch, e.message ?: e.javaClass.simpleName))
            return@LaunchedEffect
        }
        Log.i(TAG, "Map scene ready: mesh palette $palette, display blend mode ${device?.displayBlendMode}")
        try {
            launch {
                // Like a sync, a failed frame costs the map, never the app: ticking stops.
                try {
                    while (true) withFrameNanos { renderer.tick() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Map scene frame failed; the map stops updating", e)
                    halted = true
                    viewModel.report(LocalizationEvent.MeshFailed(viewModel.scene.value.epoch, e.message ?: e.javaClass.simpleName))
                }
            }
            viewModel.scene.collect { scene ->
                if (halted) return@collect
                // A SceneCore failure must cost the map display, never the app.
                try {
                    renderer.sync(scene)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Map scene update failed", e)
                    viewModel.report(LocalizationEvent.MeshFailed(scene.epoch, e.message ?: e.javaClass.simpleName))
                }
            }
        } finally {
            runCatching { renderer.release() }.onFailure { Log.e(TAG, "Map scene release failed", it) }
        }
    }
}

private const val TAG = "MapLocalization"
