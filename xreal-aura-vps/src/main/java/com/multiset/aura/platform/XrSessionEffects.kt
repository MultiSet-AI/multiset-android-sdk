/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.xr.compose.platform.LocalSession
import com.multiset.aura.pose.TrackingStatus

/** Probes the device and keeps head tracking running for the whole activity. */
@Composable
fun XrSessionEffects(xr: XrDeviceViewModel) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val device by xr.device.collectAsStateWithLifecycle()
    val grants by xr.permissionGrants.collectAsStateWithLifecycle()
    val headPose by xr.headPose.status.collectAsStateWithLifecycle()
    val session = LocalSession.current
    val isAndroidXr = device?.isAndroidXr == true

    LaunchedEffect(activity) {
        activity?.let { xr.onDeviceProbed(XrDeviceProbe.probe(it)) }
    }
    LaunchedEffect(session, isAndroidXr, grants) {
        if (session != null && isAndroidXr) xr.headPose.track(session, context)
    }
    // The user may grant a permission from system settings and come back.
    LifecycleResumeEffect(Unit) {
        val headTrackingGranted = headPose == TrackingStatus.PermissionRequired &&
            !context.isXrPermissionMissing(XrPermissions.HEAD_TRACKING)
        val sceneGranted = xr.scenePermissionMissing.value &&
            !context.isXrPermissionMissing(XrPermissions.SCENE_UNDERSTANDING_COARSE)
        if (headTrackingGranted || sceneGranted) xr.onPermissionGranted()
        onPauseOrDispose { }
    }
}

/** Returns a launcher that requests an XR permission and tells [xr] when it was granted. */
@Composable
fun rememberXrPermissionRequest(xr: XrDeviceViewModel): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) xr.onPermissionGranted()
    }
    return remember(launcher) { { permission: String -> launcher.launch(permission) } }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Activity?.displayRotationDegrees(): Int = when (this?.display?.rotation) {
    android.view.Surface.ROTATION_90 -> 90
    android.view.Surface.ROTATION_180 -> 180
    android.view.Surface.ROTATION_270 -> 270
    else -> 0
}
