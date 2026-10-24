/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multiset.aura.config.ConfigStore
import com.multiset.aura.config.LocalizationConfig
import com.multiset.aura.frame.AuraFrameSource
import com.multiset.aura.frame.FrameReport
import com.multiset.aura.pose.TrackingStatus
import com.multiset.aura.sdk.AuraSdk
import com.multiset.aura.sdk.MapTarget
import com.multiset.aura.sdk.MultiSetCredentials
import com.multiset.aura.sdk.SdkStatus
import com.multiset.aura.ui.FailureAlertGate
import com.multiset.sdk.model.MultiSetError
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MapLocalizationUiState(
    val sdk: SdkStatus,
    val target: MapTarget?,
    /** The active target's map or MapSet code. */
    val targetCode: String?,
    val tracking: TrackingStatus,
    val localization: LocalizationState,
    val config: LocalizationConfig,
    val lastFrame: FrameReport?,
    val lastFalsePositive: String?,
)

class MapLocalizationViewModel(
    application: Application,
    tracking: StateFlow<TrackingStatus>,
    frameSource: AuraFrameSource,
    private val store: ConfigStore,
) : AndroidViewModel(application) {

    private val config = MutableStateFlow(store.loadLocalization())

    private val alertGate = FailureAlertGate()
    private val _alert = MutableStateFlow<MultiSetError?>(null)

    /** A failure that deserves a modal alert, until dismissed. */
    val alert: StateFlow<MultiSetError?> = _alert.asStateFlow()

    private val controller = MapLocalizationController(
        frameSource,
        viewModelScope,
        MapMeshCache(File(application.filesDir, MESH_CACHE_DIRECTORY)),
        mapSetCode = ::activeMapSetCode,
        onError = ::onError,
    ).apply { configure(config.value) }

    val scene: StateFlow<MapScene> = controller.scene

    val uiState: StateFlow<MapLocalizationUiState> = combine(
        combine(AuraSdk.status, AuraSdk.target, ::Pair),
        tracking,
        controller.state,
        config,
        combine(frameSource.lastFrame, controller.lastFalsePositive, ::Pair),
    ) { (sdk, target), tracking, localization, config, (lastFrame, lastFalsePositive) ->
        MapLocalizationUiState(sdk, target, targetCode(target), tracking, localization, config, lastFrame, lastFalsePositive)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        MapLocalizationUiState(
            AuraSdk.status.value, AuraSdk.target.value, targetCode(AuraSdk.target.value), tracking.value,
            controller.state.value, config.value, frameSource.lastFrame.value, controller.lastFalsePositive.value,
        ),
    )

    private val _banner = MutableStateFlow<Banner?>(null)

    /** Feedback shown on the map localization screen. */
    val banner: StateFlow<Banner?> = _banner.asStateFlow()

    private val banners = BannerDecider()
    private var dismissJob: Job? = null

    init {
        viewModelScope.launch {
            var previous = controller.state.value
            controller.state.collect { current ->
                banners.next(previous, current)?.takeIf { config.value.showAlerts }?.let(::show)
                previous = current
            }
        }
        viewModelScope.launch { AuraSdk.status.collect(controller::onSdkStatus) }
        viewModelScope.launch { tracking.collect(controller::onTrackingStatus) }
        viewModelScope.launch {
            frameSource.lastProblem.collect { controller.report(LocalizationEvent.FrameProblem(it)) }
        }
    }

    fun report(event: LocalizationEvent) = controller.report(event)

    /** The map localization screen is in front and the camera may be used. */
    fun onShown() = controller.activate()

    fun onHidden() = controller.deactivate()

    fun localize() {
        alertGate.reset()
        banners.onLocalizeRequested()
        controller.localize()
    }

    fun reset() = controller.reset()

    fun dismissBanner() {
        dismissJob?.cancel()
        _banner.value = null
    }

    fun dismissAlert() {
        _alert.value = null
    }

    fun updateConfig(next: LocalizationConfig) {
        config.value = next
        store.save(next)
        controller.configure(next)
    }

    /** The in-scene mesh toggle, which is the "Show map mesh" setting. */
    fun setMeshVisible(visible: Boolean) = updateConfig(config.value.copy(showMesh = visible))

    fun setGizmoVisible(visible: Boolean) = updateConfig(config.value.copy(showGizmo = visible))

    /** Stops the session before the SDK is released; the status collector restarts it once signed in. */
    fun pauseForSdkChange() = controller.onSdkStatus(SdkStatus.Authenticating)

    /** Localizes against [target] from now on; the placed map belongs to the old target, so it goes. */
    fun switchTarget(target: MapTarget) {
        if (target == AuraSdk.target.value) return
        pauseForSdkChange()
        controller.reset()
        if (AuraSdk.switchTarget(getApplication(), target)) {
            store.saveTarget(target)
        } else {
            // The status did not change, so its collector will not undo the pause.
            controller.onSdkStatus(AuraSdk.status.value)
        }
    }

    private fun onError(error: MultiSetError) {
        if (config.value.showAlerts && alertGate.shouldShow(error)) _alert.value = error
    }

    private fun activeMapSetCode(): String? =
        AuraSdk.target.value?.takeIf { it == MapTarget.MAP_SET }?.let(::targetCode)

    private fun targetCode(target: MapTarget?): String? =
        target?.let { (AuraSdk.credentials as? MultiSetCredentials.Ready)?.codeFor(it) }

    private fun show(banner: Banner) {
        dismissJob?.cancel()
        _banner.value = banner
        banner.autoDismissMs()?.let { delayMs ->
            dismissJob = viewModelScope.launch {
                delay(delayMs)
                if (_banner.value == banner) _banner.value = null
            }
        }
    }

    override fun onCleared() {
        controller.release()
    }
}

private const val MESH_CACHE_DIRECTORY = "map-meshes"
