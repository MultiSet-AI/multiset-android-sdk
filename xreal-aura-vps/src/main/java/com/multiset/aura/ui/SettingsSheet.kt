/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.multiset.aura.R
import com.multiset.aura.config.LocalizationConfig
import com.multiset.aura.config.ObjectTrackingConfig
import com.multiset.aura.frame.FrameReport
import com.multiset.aura.sdk.MapTarget
import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.model.LocalizationMode
import com.multiset.sdk.model.QueryMode
import java.util.Locale
import kotlin.math.roundToInt

/** Full Space / Home Space switching, available only where an XR session exists. */
class SpaceControls(
    val available: Boolean,
    val isFullSpace: Boolean,
    val requestFullSpace: () -> Unit,
    val requestHomeSpace: () -> Unit,
)

/** What the sheet shows read-only; [signInFailed] enables Retry sign-in. */
data class SettingsStatus(
    val baseUrl: String,
    val lastFrame: FrameReport?,
    val frameProblem: String?,
    val lastFalsePositive: String?,
    val signInFailed: Boolean = false,
)

/**
 * The sheet's edits until Save. The host holds it above the Full/Home Space switch, which recreates
 * the sheet, and starts a fresh one on each open.
 */
class SettingsDraft(localization: LocalizationConfig, objects: ObjectTrackingConfig) {
    var localization by mutableStateOf(localization)
    var objects by mutableStateOf(objects)

    // Kept as typed: re-joining the parsed codes would eat a trailing comma mid-edit.
    var hintText by mutableStateOf(localization.hintMapCodes.joinToString(", "))
        private set

    fun editHintText(text: String) {
        hintText = text
        localization = localization.copy(hintMapCodes = parseHintMapCodes(text))
    }

    fun resetToDefaults() {
        localization = LocalizationConfig()
        objects = ObjectTrackingConfig()
        hintText = ""
    }
}

/** Settings over a scrim, not a window, so Full Space draws it; Save applies the edited [draft]. */
@Composable
fun SettingsSheet(
    draft: SettingsDraft,
    targets: List<MapTarget>,
    target: MapTarget?,
    status: SettingsStatus,
    space: SpaceControls,
    onSave: (LocalizationConfig, ObjectTrackingConfig) -> Unit,
    onTarget: (MapTarget) -> Unit,
    onResetDefaults: () -> Unit,
    onResetSession: () -> Unit,
    onRetrySignIn: () -> Unit,
    onTestFrame: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Palette.Glass)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Palette.Card,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f).fillMaxHeight(0.92f),
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_title),
                        color = Palette.Text,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_dismiss), tint = Palette.Text)
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MapLocalizationSection(
                        draft.localization,
                        draft.hintText,
                        onHintText = draft::editHintText,
                        onChange = { draft.localization = it },
                    )
                    ObjectTrackingSection(draft.objects) { draft.objects = it }
                    AdvancedSection(
                        draft.localization,
                        draft.objects,
                        onLocalization = { draft.localization = it },
                        onObjects = { draft.objects = it },
                    )
                    if (targets.size == 2) TargetSection(targets, target, onTarget)
                    EnvironmentSection(status.baseUrl)
                    StatusSection(status, onTestFrame)
                    ActionsSection(
                        space,
                        signInFailed = status.signInFailed,
                        onResetSession = onResetSession,
                        onRetrySignIn = onRetrySignIn,
                        onResetDefaults = {
                            draft.resetToDefaults()
                            onResetDefaults()
                        },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.settings_cancel), color = Palette.Text) }
                    Button(
                        onClick = {
                            onSave(draft.localization.validated(), draft.objects.validated())
                            onClose()
                        },
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.White),
                    ) {
                        Text(stringResource(R.string.settings_save), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/** Opens the settings sheet from Home and the flow screens. */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.background(Palette.Glass, CircleShape)) {
        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open), tint = Palette.Text)
    }
}

@Composable
private fun MapLocalizationSection(
    config: LocalizationConfig,
    hintText: String,
    onHintText: (String) -> Unit,
    onChange: (LocalizationConfig) -> Unit,
) {
    SectionTitle(R.string.settings_section_map)
    ChoiceRow(
        R.string.settings_mode,
        listOf(LocalizationMode.SINGLE_FRAME to R.string.settings_mode_single, LocalizationMode.MULTI_FRAME to R.string.settings_mode_multi),
        config.mode,
    ) { onChange(config.copy(mode = it)) }
    ChoiceRow(
        R.string.settings_query_mode,
        listOf(QueryMode.VPS1 to R.string.settings_query_vps1, QueryMode.VPS2 to R.string.settings_query_vps2),
        config.queryMode,
    ) { onChange(config.copy(queryMode = it)) }
    SwitchRow(R.string.settings_auto_localize, config.autoLocalize) { onChange(config.copy(autoLocalize = it)) }
    SwitchRow(R.string.settings_background_localization, config.backgroundLocalization) {
        onChange(config.copy(backgroundLocalization = it))
    }
    if (config.backgroundLocalization) {
        SliderRow(R.string.settings_background_interval, config.backgroundIntervalSeconds.toFloat(), 10f..180f, 1f, { "${it.roundToInt()} s" }) {
            onChange(config.copy(backgroundIntervalSeconds = it.roundToInt()))
        }
    }
    SwitchRow(R.string.settings_relocalization, config.relocalization) { onChange(config.copy(relocalization = it)) }
    SwitchRow(R.string.settings_first_localization, config.firstLocalizationUntilSuccess) {
        onChange(config.copy(firstLocalizationUntilSuccess = it))
    }
    if (config.mode == LocalizationMode.MULTI_FRAME) {
        SliderRow(R.string.settings_number_of_frames, config.numberOfFrames.toFloat(), 4f..6f, 1f, { it.roundToInt().toString() }) {
            onChange(config.copy(numberOfFrames = it.roundToInt()))
        }
    }
    SwitchRow(R.string.settings_confidence_check, config.confidenceCheck) { onChange(config.copy(confidenceCheck = it)) }
    if (config.confidenceCheck) {
        SliderRow(R.string.settings_confidence_threshold, config.confidenceThreshold, 0.2f..0.8f, 0.05f, { formatDecimal(it, 2) }) {
            onChange(config.copy(confidenceThreshold = it))
        }
    }
    SwitchRow(R.string.settings_pose_consistency, config.poseConsistencyCheck) { onChange(config.copy(poseConsistencyCheck = it)) }
    if (config.poseConsistencyCheck) {
        SliderRow(R.string.settings_distance_limit, config.poseConsistencyThreshold, 1.5f..15f, 0.5f, { "${formatDecimal(it, 1)} m" }) {
            onChange(config.copy(poseConsistencyThreshold = it))
        }
        SliderRow(R.string.settings_heading_limit, config.poseConsistencyYawThreshold, 10f..60f, 5f, { "${it.roundToInt()}°" }) {
            onChange(config.copy(poseConsistencyYawThreshold = it))
        }
    }
    HintCodesField(hintText, onHintText)
    SwitchRow(R.string.settings_show_alerts, config.showAlerts) { onChange(config.copy(showAlerts = it)) }
    SwitchRow(R.string.settings_show_mesh, config.showMesh) { onChange(config.copy(showMesh = it)) }
    SwitchRow(R.string.settings_show_gizmo, config.showGizmo) { onChange(config.copy(showGizmo = it)) }
    SwitchRow(R.string.settings_camera_preview, config.cameraPreview) { onChange(config.copy(cameraPreview = it)) }
}

@Composable
private fun ObjectTrackingSection(config: ObjectTrackingConfig, onChange: (ObjectTrackingConfig) -> Unit) {
    SectionTitle(R.string.settings_section_objects)
    SwitchRow(R.string.settings_auto_tracking, config.autoTracking) { onChange(config.copy(autoTracking = it)) }
    SwitchRow(R.string.settings_background_tracking, config.backgroundTracking) { onChange(config.copy(backgroundTracking = it)) }
    if (config.backgroundTracking) {
        SliderRow(R.string.settings_background_interval, config.backgroundIntervalSeconds.toFloat(), 5f..30f, 1f, { "${it.roundToInt()} s" }) {
            onChange(config.copy(backgroundIntervalSeconds = it.roundToInt()))
        }
    }
    SwitchRow(R.string.settings_restart_tracking, config.restartTracking) { onChange(config.copy(restartTracking = it)) }
    SwitchRow(R.string.settings_first_tracking, config.firstTrackingUntilSuccess) {
        onChange(config.copy(firstTrackingUntilSuccess = it))
    }
    SwitchRow(R.string.settings_show_alerts, config.showAlerts) { onChange(config.copy(showAlerts = it)) }
    SwitchRow(R.string.settings_show_outline, config.showOutline) { onChange(config.copy(showOutline = it)) }
}

@Composable
private fun AdvancedSection(
    localization: LocalizationConfig,
    objects: ObjectTrackingConfig,
    onLocalization: (LocalizationConfig) -> Unit,
    onObjects: (ObjectTrackingConfig) -> Unit,
) {
    SectionTitle(R.string.settings_section_advanced)
    SliderRow(R.string.settings_frame_interval, localization.frameCaptureIntervalMs.toFloat(), 100f..1000f, 50f, { "${it.roundToInt()} ms" }) {
        onLocalization(localization.copy(frameCaptureIntervalMs = it.roundToInt().toLong()))
    }
    SliderRow(R.string.settings_image_quality_map, localization.imageQuality.toFloat(), 50f..100f, 5f, { it.roundToInt().toString() }) {
        onLocalization(localization.copy(imageQuality = it.roundToInt()))
    }
    SliderRow(R.string.settings_capture_delay, objects.captureDelayMs.toFloat(), 0f..5000f, 100f, { "${it.roundToInt()} ms" }) {
        onObjects(objects.copy(captureDelayMs = it.roundToInt().toLong()))
    }
    SliderRow(R.string.settings_image_quality_objects, objects.imageQuality.toFloat(), 50f..100f, 5f, { it.roundToInt().toString() }) {
        onObjects(objects.copy(imageQuality = it.roundToInt()))
    }
    SwitchRow(R.string.settings_object_confidence_check, objects.confidenceCheck) { onObjects(objects.copy(confidenceCheck = it)) }
    if (objects.confidenceCheck) {
        SliderRow(R.string.settings_object_confidence_threshold, objects.confidenceThreshold, 0.2f..0.8f, 0.05f, { formatDecimal(it, 2) }) {
            onObjects(objects.copy(confidenceThreshold = it))
        }
    }
}

@Composable
private fun TargetSection(targets: List<MapTarget>, target: MapTarget?, onTarget: (MapTarget) -> Unit) {
    SectionTitle(R.string.settings_section_target)
    ChoiceRow(
        R.string.settings_target,
        targets.map { it to if (it == MapTarget.MAP) R.string.settings_target_map else R.string.settings_target_map_set },
        target,
        onSelect = onTarget,
    )
}

@Composable
private fun EnvironmentSection(baseUrl: String) {
    SectionTitle(R.string.settings_section_environment)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_base_url), color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
        Text(
            baseUrl,
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
    InfoText(
        stringResource(
            if (baseUrl == MultiSetSDKConfig.DEFAULT_BASE_URL) R.string.settings_base_url_production else R.string.settings_base_url_custom,
        ),
    )
    InfoText(stringResource(R.string.settings_image_sent))
}

@Composable
private fun StatusSection(status: SettingsStatus, onTestFrame: () -> Unit) {
    SectionTitle(R.string.settings_section_status)
    InfoText(status.lastFrame?.let { stringResource(R.string.settings_last_frame, it.summary) } ?: stringResource(R.string.settings_no_frame))
    status.frameProblem?.let { InfoText(stringResource(R.string.settings_frame_problem, it), Palette.Warning) }
    InfoText(
        status.lastFalsePositive?.let { stringResource(R.string.settings_last_false_positive, it) }
            ?: stringResource(R.string.settings_no_false_positive),
    )
    ActionButton(R.string.settings_capture_test_frame, onTestFrame)
}

@Composable
private fun ActionsSection(
    space: SpaceControls,
    signInFailed: Boolean,
    onResetSession: () -> Unit,
    onRetrySignIn: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    SectionTitle(R.string.settings_section_actions)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (space.available) {
            if (space.isFullSpace) {
                ActionButton(R.string.settings_switch_home_space, space.requestHomeSpace)
            } else {
                ActionButton(R.string.loc_switch_full_space, space.requestFullSpace)
            }
        }
        ActionButton(R.string.settings_reset_session, onResetSession)
        ActionButton(R.string.loc_retry_auth, onRetrySignIn, enabled = signInFailed)
        ActionButton(R.string.settings_reset_defaults, onResetDefaults)
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    HorizontalDivider(thickness = 2.dp, color = Palette.Outline)
    Text(stringResource(title), color = Palette.Text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun InfoText(text: String, color: Color = Palette.TextSecondary) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SwitchRow(
    @StringRes label: Int,
    checked: Boolean,
    enabled: Boolean = true,
    @StringRes disabledHint: Int? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(label),
                color = if (enabled) Palette.Text else Palette.TextDisabled,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!enabled && disabledHint != null) {
                Text(stringResource(disabledHint), color = Palette.TextDisabled, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors())
    }
}

@Composable
private fun SliderRow(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), color = Palette.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), color = Palette.Text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = { onChange(snapped(it, range.start, step)) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            colors = sliderColors(),
        )
    }
}

@Composable
private fun <T> ChoiceRow(@StringRes label: Int, options: List<Pair<T, Int>>, selected: T?, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            options.forEachIndexed { index, (value, text) ->
                val isSelected = value == selected
                SegmentedButton(
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Palette.Accent,
                        activeContentColor = Color.White,
                        inactiveContainerColor = Palette.CardRaised,
                        inactiveContentColor = Palette.Text,
                    ),
                    border = SegmentedButtonDefaults.borderStroke(if (isSelected) Palette.Accent else Palette.TextSecondary, 2.dp),
                ) {
                    Text(stringResource(text))
                }
            }
        }
    }
}

@Composable
private fun HintCodesField(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_hint_map_codes)) },
        placeholder = { Text(stringResource(R.string.settings_hint_map_codes_placeholder)) },
        supportingText = { Text(stringResource(R.string.settings_hint_map_codes_hint)) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.Text,
            unfocusedTextColor = Palette.Text,
            focusedContainerColor = Palette.CardRaised,
            unfocusedContainerColor = Palette.CardRaised,
            cursorColor = Palette.Text,
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.TextSecondary,
            focusedLabelColor = Palette.Accent,
            unfocusedLabelColor = Palette.TextSecondary,
            focusedPlaceholderColor = Palette.TextDisabled,
            unfocusedPlaceholderColor = Palette.TextDisabled,
            focusedSupportingTextColor = Palette.TextSecondary,
            unfocusedSupportingTextColor = Palette.TextSecondary,
        ),
    )
}

@Composable
private fun ActionButton(@StringRes text: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.CardRaised,
            contentColor = Palette.Text,
            disabledContainerColor = Palette.CardRaised,
            disabledContentColor = Palette.TextDisabled,
        ),
    ) {
        Text(stringResource(text), style = MaterialTheme.typography.titleSmall)
    }
}

// The M3 defaults draw dark tracks and thumbs, which an additive display shows as clear.
@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Palette.Accent,
    checkedBorderColor = Palette.Accent,
    uncheckedThumbColor = Palette.TextSecondary,
    uncheckedTrackColor = Palette.CardRaised,
    uncheckedBorderColor = Palette.TextSecondary,
    disabledCheckedThumbColor = Palette.TextDisabled,
    disabledCheckedTrackColor = Palette.Accent.copy(alpha = 0.35f),
    disabledCheckedBorderColor = Palette.Accent.copy(alpha = 0.35f),
    disabledUncheckedThumbColor = Palette.TextDisabled,
    disabledUncheckedTrackColor = Palette.CardRaised,
    disabledUncheckedBorderColor = Palette.TextDisabled,
)

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Palette.Accent,
    inactiveTrackColor = Palette.TextDisabled,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

private fun parseHintMapCodes(text: String): List<String> = text.split(',').map(String::trim).filter(String::isNotEmpty)

private fun formatDecimal(value: Float, places: Int): String = String.format(Locale.ROOT, "%.${places}f", value)

// Rounded to 3 places so `start + n * step` shows no float noise.
private fun snapped(value: Float, start: Float, step: Float): Float =
    ((start + ((value - start) / step).roundToInt() * step) * 1000f).roundToInt() / 1000f
