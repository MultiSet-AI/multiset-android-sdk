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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.xr.compose.platform.LocalSpatialCapabilities
import com.multiset.aura.R
import com.multiset.aura.camera.CameraHub
import com.multiset.aura.localization.LocalizationPhase
import com.multiset.aura.localization.LocalizationState
import com.multiset.aura.localization.MapLocalizationViewModel
import com.multiset.aura.platform.XrDeviceInfo
import com.multiset.aura.sdk.SdkStatus
import com.multiset.sdk.model.AnchorTrust
import java.util.Locale

/** Localizes against the configured map and controls what of it shows in the room. */
@Composable
fun MapLocalizationScreen(
    viewModel: MapLocalizationViewModel,
    camera: CameraHub,
    device: XrDeviceInfo?,
    cameraGranted: Boolean,
    scenePermissionMissing: Boolean,
    prompts: PromptActions,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val cameraStatus by camera.status.collectAsStateWithLifecycle()
    val localization = state.localization
    val content3d = LocalSpatialCapabilities.current.isContent3dEnabled
    val prompt = promptFor(device, cameraGranted, state.sdk, state.tracking, scenePermissionMissing, content3d)

    Box(modifier.background(Palette.Rail)) {
        Column(Modifier.align(Alignment.TopStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackButton(onBack)
                StatusPill(
                    mapStatus(state.sdk, state.tracking, cameraStatus, cameraGranted, state.config.cameraPreview, localization),
                    label = state.targetCode,
                )
            }
            (localization.phase as? LocalizationPhase.Localized)?.let { phase ->
                Text(
                    confidenceText(phase, showTrust = state.config.poseConsistencyCheck),
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        SettingsButton(onSettings, Modifier.align(Alignment.TopEnd).padding(24.dp))
        banner?.let { BannerToast(it, FlowCopy.MAP, viewModel::dismissBanner, Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) }
        prompt?.let {
            PromptCard(it, FlowCopy.MAP, { prompts.run(it) }, Modifier.align(Alignment.Center).padding(24.dp), detail = state.sdk.problem())
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MeshToggle(localization.mesh, state.config.showMesh, viewModel::setMeshVisible, FlowCopy.MAP)
            if (localization.phase is LocalizationPhase.Localized) GizmoToggle(state.config.showGizmo, viewModel::setGizmoVisible)
            LocalizeButton(
                localization,
                enabled = state.sdk == SdkStatus.Ready && cameraGranted && state.tracking.isUsable(),
                onClick = viewModel::localize,
            )
            ResetButton(viewModel::reset)
        }
        if (state.config.cameraPreview) {
            CameraThumbnail(camera, cameraGranted, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }
    }
    FailureAlert(alert, R.string.error_title_localization, viewModel::dismissAlert)
}

@Composable
private fun LocalizeButton(localization: LocalizationState, enabled: Boolean, onClick: () -> Unit) {
    val phase = localization.phase
    val working = localization.isWorking
    Button(
        onClick = onClick,
        enabled = enabled && !working,
        modifier = Modifier.height(52.dp),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Color.White,
            disabledContainerColor = Palette.Glass,
            disabledContentColor = Palette.TextSecondary,
        ),
    ) {
        if (working) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.TextSecondary)
        } else {
            val again = phase is LocalizationPhase.Localized || phase is LocalizationPhase.Failed
            Icon(if (again) Icons.Filled.Refresh else Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(
                when {
                    working -> R.string.home_localizing
                    phase is LocalizationPhase.Localized -> R.string.home_relocalize
                    phase is LocalizationPhase.Failed -> R.string.home_try_again
                    else -> R.string.home_localize
                },
            ),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun GizmoToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .height(52.dp)
            .clip(PillShape)
            .background(if (on) Palette.Accent else Palette.Glass)
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.loc_gizmo), color = Palette.Text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun confidenceText(phase: LocalizationPhase.Localized, showTrust: Boolean): String {
    val confidence = phase.confidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "–"
    return if (showTrust) {
        stringResource(R.string.loc_confidence_trust, confidence, stringResource(trustLabel(phase.trust)))
    } else {
        stringResource(R.string.loc_confidence, confidence)
    }
}

private fun trustLabel(trust: AnchorTrust): Int = when (trust) {
    AnchorTrust.PROVISIONAL -> R.string.loc_trust_provisional
    AnchorTrust.CORROBORATED -> R.string.loc_trust_corroborated
    AnchorTrust.TRUSTED -> R.string.loc_trust_trusted
}
