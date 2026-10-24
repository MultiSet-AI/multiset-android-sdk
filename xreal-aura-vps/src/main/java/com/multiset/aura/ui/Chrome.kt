/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiset.aura.R
import com.multiset.aura.camera.CameraHub
import com.multiset.aura.camera.CameraPreview
import com.multiset.aura.localization.Banner
import com.multiset.aura.localization.MeshStatus
import com.multiset.aura.platform.XrDeviceInfo
import com.multiset.aura.platform.XrPermissions
import com.multiset.aura.pose.TrackingStatus
import com.multiset.aura.sdk.SdkStatus

/** What a flow finds and shows, by name: the map and its mesh, or objects and their outlines. */
enum class FlowCopy(
    @get:StringRes val found: Int,
    @get:StringRes val failed: Int,
    @get:StringRes val showMesh: Int,
    @get:StringRes val hideMesh: Int,
    @get:StringRes val loadingMesh: Int,
    @get:StringRes val cameraNeeded: Int,
    @get:StringRes val sceneNeeded: Int,
    @get:StringRes val fullSpaceNeeded: Int,
) {
    MAP(
        R.string.home_banner_success,
        R.string.home_banner_failure,
        R.string.home_mesh_show,
        R.string.home_mesh_hide,
        R.string.home_mesh_downloading,
        R.string.loc_camera_needed,
        R.string.loc_scene_needed,
        R.string.loc_mesh_full_space,
    ),
    OBJECTS(
        R.string.obj_banner_success,
        R.string.obj_banner_failure,
        R.string.obj_outline_show,
        R.string.obj_outline_hide,
        R.string.obj_outline_downloading,
        R.string.obj_camera_needed,
        R.string.obj_scene_needed,
        R.string.obj_full_space,
    ),
}

/** The one thing to do before a flow can run. */
enum class Prompt {
    CAMERA_PERMISSION,
    NOT_CONFIGURED,
    SIGN_IN_FAILED,
    HEAD_TRACKING_PERMISSION,
    SCENE_PERMISSION,
    NEEDS_FULL_SPACE,
    NOT_ANDROID_XR,
}

/**
 * The most fundamental prompt that applies, or null. A device that isn't Android XR comes first,
 * since nothing a later prompt asks for can make the flow run there. The scene prompts read the
 * permission and the space as they are now, not what the scene last reported, so neither outlives
 * its fix.
 */
fun promptFor(
    device: XrDeviceInfo?,
    cameraGranted: Boolean,
    sdk: SdkStatus,
    tracking: TrackingStatus,
    scenePermissionMissing: Boolean,
    content3dEnabled: Boolean,
): Prompt? = when {
    device?.isAndroidXr == false -> Prompt.NOT_ANDROID_XR
    !cameraGranted -> Prompt.CAMERA_PERMISSION
    sdk is SdkStatus.NotConfigured -> Prompt.NOT_CONFIGURED
    sdk is SdkStatus.AuthFailed -> Prompt.SIGN_IN_FAILED
    tracking == TrackingStatus.PermissionRequired -> Prompt.HEAD_TRACKING_PERMISSION
    scenePermissionMissing -> Prompt.SCENE_PERMISSION
    !content3dEnabled -> Prompt.NEEDS_FULL_SPACE
    else -> null
}

/** What the prompt card's button does; the activity owns the permission launchers and the space. */
class PromptActions(
    val requestCamera: () -> Unit,
    val retrySignIn: () -> Unit,
    val requestXrPermission: (String) -> Unit,
    val requestFullSpace: () -> Unit,
) {
    fun run(prompt: Prompt) = when (prompt) {
        Prompt.CAMERA_PERMISSION -> requestCamera()
        Prompt.SIGN_IN_FAILED -> retrySignIn()
        Prompt.HEAD_TRACKING_PERMISSION -> requestXrPermission(XrPermissions.HEAD_TRACKING)
        Prompt.SCENE_PERMISSION -> requestXrPermission(XrPermissions.SCENE_UNDERSTANDING_COARSE)
        Prompt.NEEDS_FULL_SPACE -> requestFullSpace()
        Prompt.NOT_CONFIGURED, Prompt.NOT_ANDROID_XR -> Unit
    }
}

/** Before the status pill on the flow screens: back to Home. */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.background(Palette.Glass, CircleShape)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_back), tint = Palette.Text)
    }
}

/** Top-left of the screen: what is being localized against, and the single most important status. */
@Composable
fun StatusPill(status: HomeStatus, label: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Palette.Glass, PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val waiting = when (status) {
            HomeStatus.Localizing, HomeStatus.ObjectsSearching -> true
            is HomeStatus.Localized -> status.refreshing
            is HomeStatus.ObjectsFound -> status.refreshing
            else -> false
        }
        if (waiting) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = status.tone.color())
        } else {
            Box(Modifier.size(8.dp).background(status.tone.color(), CircleShape))
        }
        label?.let {
            Text(
                it,
                color = Palette.Text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text("·", color = Palette.TextSecondary, style = MaterialTheme.typography.labelLarge)
        }
        Text(status.text(), color = Palette.TextSecondary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The in-scene mesh switch; it appears once a mesh is loading or loaded. */
@Composable
fun MeshToggle(state: MeshStatus, on: Boolean, onToggle: (Boolean) -> Unit, copy: FlowCopy, modifier: Modifier = Modifier) {
    if (state != MeshStatus.Loading && state != MeshStatus.Shown && state != MeshStatus.Hidden) return
    val loading = state == MeshStatus.Loading
    // Black shows as clear on the additive display, so the lit state is the accent, not a dark icon.
    val lit = on && !loading
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(
            onClick = { onToggle(!on) },
            enabled = !loading,
            modifier = Modifier.size(52.dp).background(if (lit) Palette.Accent else Palette.Glass, CircleShape),
        ) {
            Icon(
                painterResource(R.drawable.ic_mesh_grid),
                contentDescription = stringResource(
                    when {
                        loading -> copy.loadingMesh
                        on -> copy.hideMesh
                        else -> copy.showMesh
                    },
                ),
                tint = Palette.Text,
            )
        }
        if (loading) CircularProgressIndicator(Modifier.size(52.dp), strokeWidth = 2.dp, color = Palette.Accent)
    }
}

/** A compact notice at the top of the screen: success fades by itself, failures wait to be dismissed. */
@Composable
fun BannerToast(banner: Banner, copy: FlowCopy, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val (color, text) = when (banner) {
        is Banner.Success -> Palette.Success to stringResource(copy.found, banner.label, confidencePercent(banner.confidence))
        is Banner.Failure -> Palette.Danger to stringResource(copy.failed, banner.message)
        is Banner.Warning -> Palette.Warning to stringResource(R.string.home_banner_warning, banner.message)
    }
    Surface(modifier = modifier.widthIn(max = 520.dp), shape = RoundedCornerShape(16.dp), color = Palette.Rail.copy(alpha = 0.92f)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (banner is Banner.Success) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(text, color = Palette.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_dismiss), tint = Palette.TextSecondary) }
        }
    }
}

/** The one thing to do before the flow can run; [detail] fills the SDK prompts' reason. */
@Composable
fun PromptCard(prompt: Prompt, copy: FlowCopy, onAction: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    Surface(modifier = modifier.widthIn(max = 480.dp), shape = RoundedCornerShape(20.dp), color = Palette.Rail.copy(alpha = 0.94f)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(prompt.text(copy, detail), color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
            prompt.action?.let { label ->
                Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.White)) {
                    Text(stringResource(label))
                }
            }
        }
    }
}

/** Bottom row of the flow screens: drops what the flow placed and starts over. */
@Composable
fun ResetButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(52.dp),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.CardRaised, contentColor = Palette.Text),
    ) {
        Text(stringResource(R.string.loc_reset), style = MaterialTheme.typography.titleSmall)
    }
}

/** Bottom-right of the flow screens: what the world camera sees, framed like the SDK frame. */
@Composable
fun CameraThumbnail(camera: CameraHub, cameraGranted: Boolean, modifier: Modifier = Modifier) {
    CameraPreview(
        camera,
        cameraGranted,
        modifier
            .size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Card),
    )
}

/** The controllers wait out tracking that is starting or paused; the rest never comes back. */
fun TrackingStatus.isUsable() =
    this == TrackingStatus.Tracking || this == TrackingStatus.Starting || this is TrackingStatus.NotTracking

/** The reason the SDK prompts show, if any. */
fun SdkStatus.problem(): String? = when (this) {
    is SdkStatus.NotConfigured -> reason
    is SdkStatus.AuthFailed -> message
    SdkStatus.Authenticating, SdkStatus.Ready -> null
}

@Composable
private fun Prompt.text(copy: FlowCopy, detail: String?): String = when (this) {
    Prompt.CAMERA_PERMISSION -> stringResource(copy.cameraNeeded)
    Prompt.NOT_CONFIGURED -> stringResource(R.string.loc_sdk_not_configured, detail.orEmpty())
    Prompt.SIGN_IN_FAILED -> stringResource(R.string.loc_sdk_auth_failed, detail.orEmpty())
    Prompt.HEAD_TRACKING_PERMISSION -> stringResource(R.string.loc_head_tracking_needed)
    Prompt.SCENE_PERMISSION -> stringResource(copy.sceneNeeded)
    Prompt.NEEDS_FULL_SPACE -> stringResource(copy.fullSpaceNeeded)
    Prompt.NOT_ANDROID_XR -> stringResource(R.string.loc_not_android_xr)
}

private val Prompt.action: Int?
    @StringRes get() = when (this) {
        Prompt.CAMERA_PERMISSION -> R.string.capture_permission_grant
        Prompt.SIGN_IN_FAILED -> R.string.loc_retry_auth
        Prompt.HEAD_TRACKING_PERMISSION -> R.string.capture_head_tracking_grant
        Prompt.SCENE_PERMISSION -> R.string.loc_grant_scene
        Prompt.NEEDS_FULL_SPACE -> R.string.loc_switch_full_space
        Prompt.NOT_CONFIGURED, Prompt.NOT_ANDROID_XR -> null
    }

@Composable
private fun HomeStatus.text(): String = when (this) {
    HomeStatus.NotConfigured -> stringResource(R.string.status_not_configured)
    HomeStatus.SignInFailed -> stringResource(R.string.status_sign_in_failed)
    HomeStatus.SigningIn -> stringResource(R.string.status_signing_in)
    HomeStatus.CameraOff -> stringResource(R.string.status_camera_off)
    HomeStatus.CameraStarting -> stringResource(R.string.status_camera_starting)
    HomeStatus.CameraUnavailable -> stringResource(R.string.status_camera_unavailable)
    HomeStatus.TrackingStarting -> stringResource(R.string.status_tracking_starting)
    HomeStatus.TrackingLost -> stringResource(R.string.status_tracking_lost)
    HomeStatus.TrackingUnavailable -> stringResource(R.string.status_tracking_unavailable)
    HomeStatus.NotLocalized -> stringResource(R.string.status_not_localized)
    HomeStatus.Localizing -> stringResource(R.string.status_localizing)
    HomeStatus.LocalizationFailed -> stringResource(R.string.status_localization_failed)
    is HomeStatus.Localized -> confidence?.let { stringResource(R.string.status_localized_confidence, (it * 100).toInt()) }
        ?: stringResource(R.string.status_localized)
    HomeStatus.ObjectsIdle -> stringResource(R.string.status_objects_idle)
    HomeStatus.ObjectsSearching -> stringResource(R.string.status_objects_searching)
    is HomeStatus.ObjectsFound -> confidence?.let { pluralStringResource(R.plurals.status_objects_found_confidence, count, count, (it * 100).toInt()) }
        ?: pluralStringResource(R.plurals.status_objects_found, count, count)
    HomeStatus.ObjectsFailed -> stringResource(R.string.status_objects_failed)
}

private fun StatusTone.color() = when (this) {
    StatusTone.GOOD -> Palette.Success
    StatusTone.BUSY -> Palette.Warning
    StatusTone.BAD -> Palette.Danger
    StatusTone.IDLE -> Palette.Idle
}

private fun confidencePercent(confidence: Float?) = confidence?.let { "${(it * 100).toInt()}%" } ?: "–"

val PillShape = RoundedCornerShape(999.dp)

// 16:9, Aura's whole camera frame, which is what the SDK is sent.
private val THUMBNAIL_WIDTH = 320.dp
private val THUMBNAIL_HEIGHT = 180.dp
