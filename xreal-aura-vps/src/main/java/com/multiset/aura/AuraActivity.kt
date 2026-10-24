/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.requestFullSpace
import androidx.xr.compose.platform.requestHomeSpace
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import com.multiset.aura.config.ConfigStore
import com.multiset.aura.config.LocalizationConfig
import com.multiset.aura.config.ObjectTrackingConfig
import com.multiset.aura.localization.MapLocalizationViewModel
import com.multiset.aura.objecttracking.ObjectTrackingViewModel
import com.multiset.aura.platform.XrDeviceViewModel
import com.multiset.aura.platform.XrSessionEffects
import com.multiset.aura.platform.displayRotationDegrees
import com.multiset.aura.platform.rememberXrPermissionRequest
import com.multiset.aura.scene.MapSceneEffect
import com.multiset.aura.scene.ObjectSceneEffect
import com.multiset.aura.sdk.AuraSdk
import com.multiset.aura.sdk.MultiSetCredentials
import com.multiset.aura.sdk.SdkStatus
import com.multiset.aura.ui.AuraTheme
import com.multiset.aura.ui.HomeScreen
import com.multiset.aura.ui.MapLocalizationScreen
import com.multiset.aura.ui.ObjectTrackingScreen
import com.multiset.aura.ui.PromptActions
import com.multiset.aura.ui.SettingsDraft
import com.multiset.aura.ui.SettingsSheet
import com.multiset.aura.ui.SettingsStatus
import com.multiset.aura.ui.SpaceControls
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKConfig
import com.multiset.sdk.ui.MultiSetWatermark
import kotlinx.coroutines.launch

class AuraActivity : ComponentActivity() {

    private enum class Screen { HOME, MAP, OBJECTS }

    private val store by lazy { ConfigStore(applicationContext) }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuraSdk.initialize(this, store.loadTarget())

        setContent {
            AuraTheme {
                val xr = viewModel { XrDeviceViewModel(application) }
                val map = viewModel { MapLocalizationViewModel(application, xr.tracking, xr.frameSource, store) }
                val objects = viewModel { ObjectTrackingViewModel(application, xr.tracking, xr.frameSource, store) }
                XrSessionEffects(xr)
                MapSceneEffect(map, xr)
                ObjectSceneEffect(objects, xr)

                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                // Above the space branch below, which recreates the sheet; closing drops it.
                val settingsDraft = remember(settingsOpen) { SettingsDraft(map.uiState.value.config, objects.uiState.value.config) }
                var cameraGranted by remember { mutableStateOf(hasCameraPermission()) }
                val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    cameraGranted = granted
                }
                val requestXrPermission = rememberXrPermissionRequest(xr)

                LifecycleResumeEffect(Unit) {
                    // The user may grant the camera permission from system settings and come back.
                    cameraGranted = hasCameraPermission()
                    xr.setDisplayRotation(displayRotationDegrees())
                    onPauseOrDispose { }
                }
                // Above the space switch, so changing space neither stops nor restarts the session.
                LifecycleResumeEffect(screen, cameraGranted) {
                    if (screen == Screen.MAP && cameraGranted) map.onShown()
                    onPauseOrDispose { map.onHidden() }
                }
                LifecycleResumeEffect(screen, cameraGranted) {
                    if (screen == Screen.OBJECTS && cameraGranted) objects.onShown()
                    onPauseOrDispose { objects.onHidden() }
                }
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                // Registered last, so it takes Back before the screen does.
                BackHandler(enabled = settingsOpen) { settingsOpen = false }

                val sdk by AuraSdk.status.collectAsStateWithLifecycle()
                val target by AuraSdk.target.collectAsStateWithLifecycle()
                val device by xr.device.collectAsStateWithLifecycle()
                val scenePermissionMissing by xr.scenePermissionMissing.collectAsStateWithLifecycle()
                val prompts = PromptActions(
                    requestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                    retrySignIn = { AuraSdk.retry(applicationContext) },
                    requestXrPermission = requestXrPermission,
                    requestFullSpace = { lifecycleScope.launch { requestFullSpace() } },
                )
                val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled
                val space = SpaceControls(
                    available = LocalSession.current != null,
                    isFullSpace = spatial,
                    requestFullSpace = prompts.requestFullSpace,
                    requestHomeSpace = { lifecycleScope.launch { requestHomeSpace() } },
                )
                val content: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier) {
                        when (screen) {
                            Screen.HOME -> HomeScreen(
                                sdk, target, AuraSdk.credentials, device,
                                onMapLocalization = { screen = Screen.MAP },
                                onObjectTracking = { screen = Screen.OBJECTS },
                                onRetrySignIn = prompts.retrySignIn,
                                onSettings = { settingsOpen = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            Screen.MAP -> MapLocalizationScreen(
                                map, xr.camera, device, cameraGranted, scenePermissionMissing, prompts,
                                onBack = { screen = Screen.HOME },
                                onSettings = { settingsOpen = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            Screen.OBJECTS -> {
                                val mapState by map.uiState.collectAsStateWithLifecycle()
                                ObjectTrackingScreen(
                                    objects, xr.camera, device, cameraGranted, scenePermissionMissing,
                                    cameraPreview = mapState.config.cameraPreview,
                                    prompts = prompts,
                                    onBack = { screen = Screen.HOME },
                                    onSettings = { settingsOpen = true },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        if (settingsOpen) {
                            val mapState by map.uiState.collectAsStateWithLifecycle()
                            val frameProblem by xr.frameSource.lastProblem.collectAsStateWithLifecycle()
                            val targets = (AuraSdk.credentials as? MultiSetCredentials.Ready)?.targets.orEmpty()
                            SettingsSheet(
                                draft = settingsDraft,
                                targets = targets,
                                target = target,
                                status = SettingsStatus(
                                    baseUrl = activeBaseUrl(),
                                    lastFrame = mapState.lastFrame,
                                    frameProblem = frameProblem,
                                    lastFalsePositive = mapState.lastFalsePositive,
                                    signInFailed = sdk is SdkStatus.AuthFailed,
                                ),
                                space = space,
                                onSave = { localization, objectTracking ->
                                    map.updateConfig(localization)
                                    objects.updateConfig(objectTracking)
                                },
                                onTarget = { next ->
                                    // Only a real switch changes the SDK status, which is what resumes object tracking.
                                    if (next != AuraSdk.target.value && next in targets) objects.pauseForSdkChange()
                                    map.switchTarget(next)
                                },
                                onResetDefaults = {
                                    store.resetToDefaults()
                                    map.updateConfig(LocalizationConfig())
                                    objects.updateConfig(ObjectTrackingConfig())
                                },
                                onResetSession = {
                                    when (screen) {
                                        Screen.MAP -> map.reset()
                                        Screen.OBJECTS -> objects.reset()
                                        Screen.HOME -> {
                                            map.reset()
                                            objects.reset()
                                        }
                                    }
                                },
                                onRetrySignIn = prompts.retrySignIn,
                                onTestFrame = xr::captureTestFrame,
                                onClose = { settingsOpen = false },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                if (spatial) {
                    Subspace {
                        SpatialPanel(SubspaceModifier.width(1280.dp).height(800.dp).resizable().movable()) {
                            Surface { content(Modifier.fillMaxSize()) }
                        }
                    }
                } else {
                    Surface(Modifier.fillMaxSize()) { content(Modifier.fillMaxSize()) }
                }
            }
        }
        MultiSetWatermark.attach(this)
    }

    // The SDK reports its production default until initialize has applied a configured override.
    private fun activeBaseUrl(): String =
        (AuraSdk.credentials as? MultiSetCredentials.Ready)?.baseUrl?.let(MultiSetSDKConfig::normalizeBaseUrl)
            ?: MultiSetSDK.activeBaseUrl

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
