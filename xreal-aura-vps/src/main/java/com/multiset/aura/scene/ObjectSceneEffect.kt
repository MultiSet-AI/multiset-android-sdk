/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import com.multiset.aura.objecttracking.ObjectScene
import com.multiset.aura.objecttracking.ObjectTrackingEvent
import com.multiset.aura.objecttracking.ObjectTrackingViewModel
import com.multiset.aura.platform.XrDeviceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Keeps the tracked objects' outlines in the room in step with [viewModel]'s scene. Place it above
 * the space-mode switch so the outlines survive changing between Home Space and Full Space.
 */
@Composable
fun ObjectSceneEffect(viewModel: ObjectTrackingViewModel, xr: XrDeviceViewModel) {
    val context = LocalContext.current
    val session = LocalSession.current
    val device by xr.device.collectAsStateWithLifecycle()
    val grants by xr.permissionGrants.collectAsStateWithLifecycle()
    val content3d = LocalSpatialCapabilities.current.isContent3dEnabled
    val isAndroidXr = device?.isAndroidXr == true

    LaunchedEffect(session, isAndroidXr, content3d, grants) {
        if (session == null || !isAndroidXr) return@LaunchedEffect
        // Once ticking stops the outlines freeze, so none may be reported shown over that failure.
        var halted = false
        val report: (ObjectTrackingEvent) -> Unit = { if (!halted) viewModel.report(it) }
        val renderer = try {
            ObjectSpaceRenderer(context, session, this, report, viewModel::onBakeFailed)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the object scene", e)
            viewModel.reportSceneFailure(viewModel.scene.value, e)
            return@LaunchedEffect
        }
        try {
            launch {
                // Like a sync, a failed frame costs the outlines, never the app: ticking stops.
                try {
                    while (true) withFrameNanos { renderer.tick() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Object scene frame failed; the outlines stop updating", e)
                    halted = true
                    viewModel.reportSceneFailure(viewModel.scene.value, e)
                }
            }
            viewModel.scene.collect { scene ->
                if (halted) return@collect
                // A SceneCore failure must cost the outlines, never the app.
                try {
                    renderer.sync(scene)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Object scene update failed", e)
                    viewModel.reportSceneFailure(scene, e)
                }
            }
        } finally {
            runCatching { renderer.release() }.onFailure { Log.e(TAG, "Object scene release failed", it) }
        }
    }
}

/** The scene failed as a whole, so every object it places reports the failure. */
private fun ObjectTrackingViewModel.reportSceneFailure(scene: ObjectScene, e: Exception) {
    val message = e.message ?: e.javaClass.simpleName
    scene.placements.keys.forEach { report(ObjectTrackingEvent.MeshFailed(scene.epoch, it, message)) }
}

private const val TAG = "AuraObjects"
