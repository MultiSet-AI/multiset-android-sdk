/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.xr.compose.platform.LocalSpatialCapabilities
import com.multiset.aura.R
import com.multiset.aura.camera.CameraHub
import com.multiset.aura.localization.MeshStatus
import com.multiset.aura.objecttracking.ObjectPhase
import com.multiset.aura.objecttracking.ObjectTrackingViewModel
import com.multiset.aura.objecttracking.TrackedObject
import com.multiset.aura.platform.XrDeviceInfo
import com.multiset.aura.sdk.SdkStatus
import java.util.Locale

/**
 * Tracks the configured objects and outlines each one found in the room. [cameraPreview] is the
 * "Camera preview" setting, which covers both flows.
 */
@Composable
fun ObjectTrackingScreen(
    viewModel: ObjectTrackingViewModel,
    camera: CameraHub,
    device: XrDeviceInfo?,
    cameraGranted: Boolean,
    scenePermissionMissing: Boolean,
    cameraPreview: Boolean,
    prompts: PromptActions,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val cameraStatus by camera.status.collectAsStateWithLifecycle()
    val objects = state.objects
    val meshes = objects.meshes.values
    val content3d = LocalSpatialCapabilities.current.isContent3dEnabled
    val prompt = promptFor(device, cameraGranted, state.sdk, state.tracking, scenePermissionMissing, content3d)
    val hasCodes = state.codes.isNotEmpty()

    Box(modifier.background(Palette.Rail)) {
        Column(Modifier.align(Alignment.TopStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackButton(onBack)
                StatusPill(
                    objectStatus(state.sdk, state.tracking, cameraStatus, cameraGranted, cameraPreview, objects),
                    label = state.codes.joinToString().ifEmpty { null },
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            FlowRow(
                Modifier.widthIn(max = 720.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                objects.objects.values.forEach { ObjectBadge(it) }
            }
        }
        SettingsButton(onSettings, Modifier.align(Alignment.TopEnd).padding(24.dp))
        banner?.let { BannerToast(it, FlowCopy.OBJECTS, viewModel::dismissBanner, Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) }
        when {
            prompt != null -> PromptCard(
                prompt, FlowCopy.OBJECTS, { prompts.run(prompt) },
                Modifier.align(Alignment.Center).padding(24.dp), detail = state.sdk.problem(),
            )
            // Home only opens this screen with object codes; this keeps an empty list harmless.
            !hasCodes -> NoCodesCard(Modifier.align(Alignment.Center).padding(24.dp))
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MeshToggle(outlineToggleState(meshes), state.config.showOutline, viewModel::setOutlineVisible, FlowCopy.OBJECTS)
            TrackButton(
                objects.phase,
                enabled = hasCodes && state.sdk == SdkStatus.Ready && cameraGranted && state.tracking.isUsable(),
                onClick = viewModel::track,
            )
            ResetButton(viewModel::reset)
        }
        if (cameraPreview) CameraThumbnail(camera, cameraGranted, Modifier.align(Alignment.BottomEnd).padding(24.dp))
    }
    FailureAlert(alert, R.string.error_title_object_tracking, viewModel::dismissAlert)
}

@Composable
private fun ObjectBadge(tracked: TrackedObject) {
    Row(
        Modifier
            .background(Palette.Glass, PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(Palette.Success, CircleShape))
        Text(tracked.code, color = Palette.Text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.loc_confidence, tracked.confidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "–"),
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TrackButton(phase: ObjectPhase, enabled: Boolean, onClick: () -> Unit) {
    val searching = phase == ObjectPhase.Searching || phase == ObjectPhase.WaitingForTracking
    Button(
        onClick = onClick,
        enabled = enabled && !searching,
        modifier = Modifier.height(52.dp),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Color.White,
            disabledContainerColor = Palette.Glass,
            disabledContentColor = Palette.TextSecondary,
        ),
    ) {
        if (searching) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.TextSecondary)
        } else {
            Icon(if (phase == ObjectPhase.Found) Icons.Filled.Refresh else Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(
                when {
                    searching -> R.string.obj_searching
                    phase == ObjectPhase.Found -> R.string.obj_restart
                    else -> R.string.obj_track
                },
            ),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun NoCodesCard(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.widthIn(max = 480.dp), shape = RoundedCornerShape(20.dp), color = Palette.Rail.copy(alpha = 0.94f)) {
        Text(
            stringResource(R.string.home_objects_unavailable),
            color = Palette.Text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(20.dp),
        )
    }
}

/** One switch for every outline: usable once any outline is shown, spinning while one loads. */
private fun outlineToggleState(meshes: Collection<MeshStatus>): MeshStatus =
    meshes.firstOrNull { it == MeshStatus.Shown || it == MeshStatus.Hidden }
        ?: meshes.firstOrNull { it == MeshStatus.Loading }
        ?: MeshStatus.Off
