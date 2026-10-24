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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multiset.aura.R
import com.multiset.aura.platform.XrDeviceInfo
import com.multiset.aura.sdk.MapTarget
import com.multiset.aura.sdk.MultiSetCredentials
import com.multiset.aura.sdk.SdkStatus
import com.multiset.sdk.MultiSetSDK

/** Where the sample starts: what the SDK was configured with, and the flows that can run with it. */
@Composable
fun HomeScreen(
    sdk: SdkStatus,
    target: MapTarget?,
    credentials: MultiSetCredentials,
    device: XrDeviceInfo?,
    onMapLocalization: () -> Unit,
    onObjectTracking: () -> Unit,
    onRetrySignIn: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = credentials as? MultiSetCredentials.Ready
    Column(
        modifier
            .background(Palette.Rail)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_name),
                color = Palette.Text,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            SettingsButton(onSettings)
        }
        if (device?.isAndroidXr == false) PromptCard(Prompt.NOT_ANDROID_XR, FlowCopy.MAP, onAction = {})
        Surface(Modifier.fillMaxWidth(), shape = CARD, color = Palette.Card) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine(sdkText(sdk), primary = true)
                InfoLine(targetText(ready, target))
                InfoLine(
                    ready?.objectCodes?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.home_objects, it.joinToString()) }
                        ?: stringResource(R.string.home_objects_none),
                )
                InfoLine(stringResource(R.string.home_sdk_version, MultiSetSDK.VERSION))
                InfoLine(device?.summary ?: stringResource(R.string.home_device_checking))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val hasTarget = ready?.targets?.isNotEmpty() == true
            FlowCard(
                title = R.string.home_map_localization,
                text = if (hasTarget) R.string.home_map_localization_body else R.string.home_map_unavailable,
                enabled = hasTarget,
                onClick = onMapLocalization,
                modifier = Modifier.weight(1f),
            )
            val hasObjects = ready?.objectCodes?.isNotEmpty() == true
            FlowCard(
                title = R.string.home_object_tracking,
                text = if (hasObjects) R.string.home_object_tracking_body else R.string.home_objects_unavailable,
                enabled = hasObjects,
                onClick = onObjectTracking,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = onRetrySignIn,
            enabled = sdk is SdkStatus.AuthFailed,
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.Accent,
                contentColor = Color.White,
                disabledContainerColor = Palette.CardRaised,
                disabledContentColor = Palette.TextDisabled,
            ),
        ) {
            Text(stringResource(R.string.loc_retry_auth))
        }
    }
}

@Composable
private fun InfoLine(text: String, primary: Boolean = false) {
    Text(
        text,
        color = if (primary) Palette.Text else Palette.TextSecondary,
        style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun FlowCard(@StringRes title: Int, @StringRes text: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 140.dp),
        shape = CARD,
        color = if (enabled) Palette.CardRaised else Palette.Card,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), color = if (enabled) Palette.Text else Palette.TextDisabled, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(text), color = if (enabled) Palette.TextSecondary else Palette.TextDisabled, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun sdkText(sdk: SdkStatus): String = when (sdk) {
    is SdkStatus.NotConfigured -> stringResource(R.string.loc_sdk_not_configured, sdk.reason)
    SdkStatus.Authenticating -> stringResource(R.string.loc_sdk_authenticating)
    SdkStatus.Ready -> stringResource(R.string.loc_sdk_ready)
    is SdkStatus.AuthFailed -> stringResource(R.string.loc_sdk_auth_failed, sdk.message)
}

@Composable
private fun targetText(ready: MultiSetCredentials.Ready?, target: MapTarget?): String = when (target) {
    MapTarget.MAP -> stringResource(R.string.home_target_map, ready?.codeFor(MapTarget.MAP).orEmpty())
    MapTarget.MAP_SET -> stringResource(R.string.home_target_map_set, ready?.codeFor(MapTarget.MAP_SET).orEmpty())
    null -> stringResource(R.string.loc_target_missing)
}

private val CARD = RoundedCornerShape(20.dp)
