/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.objecttracking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multiset.aura.config.ConfigStore
import com.multiset.aura.config.ObjectTrackingConfig
import com.multiset.aura.frame.AuraFrameSource
import com.multiset.aura.localization.Banner
import com.multiset.aura.localization.autoDismissMs
import com.multiset.aura.pose.TrackingStatus
import com.multiset.aura.sdk.AuraSdk
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

data class ObjectTrackingUiState(
    val sdk: SdkStatus,
    /** The object codes from `multiset.properties`. */
    val codes: List<String>,
    val tracking: TrackingStatus,
    val objects: ObjectTrackingState,
    val config: ObjectTrackingConfig,
)

class ObjectTrackingViewModel(
    application: Application,
    tracking: StateFlow<TrackingStatus>,
    frameSource: AuraFrameSource,
    private val store: ConfigStore,
) : AndroidViewModel(application) {

    private val codes = (AuraSdk.credentials as? MultiSetCredentials.Ready)?.objectCodes.orEmpty()

    private val config = MutableStateFlow(store.loadObjectTracking())

    private val alertGate = FailureAlertGate()
    private val _alert = MutableStateFlow<MultiSetError?>(null)

    /** A failure that deserves a modal alert, until dismissed. */
    val alert: StateFlow<MultiSetError?> = _alert.asStateFlow()

    private val controller = ObjectTrackingController(
        frameSource,
        codes,
        viewModelScope,
        ObjectMeshCache(File(application.filesDir, MESH_CACHE_DIRECTORY)),
        onError = ::onError,
    ).apply { configure(config.value) }

    val scene: StateFlow<ObjectScene> = controller.scene

    val uiState: StateFlow<ObjectTrackingUiState> = combine(AuraSdk.status, tracking, controller.state, config) { sdk, tracking, objects, config ->
        ObjectTrackingUiState(sdk, codes, tracking, objects, config)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ObjectTrackingUiState(AuraSdk.status.value, codes, tracking.value, controller.state.value, config.value),
    )

    private val _banner = MutableStateFlow<Banner?>(null)

    /** Feedback shown on the object tracking screen. */
    val banner: StateFlow<Banner?> = _banner.asStateFlow()

    private var trackRequested = false
    private var dismissJob: Job? = null

    init {
        viewModelScope.launch {
            var previous = controller.state.value
            controller.state.collect { current ->
                nextObjectBanner(previous, current, trackRequested)?.let { banner ->
                    trackRequested = false
                    if (config.value.showAlerts) show(banner)
                }
                previous = current
            }
        }
        viewModelScope.launch { AuraSdk.status.collect(controller::onSdkStatus) }
        viewModelScope.launch { tracking.collect(controller::onTrackingStatus) }
        viewModelScope.launch {
            frameSource.lastProblem.collect { controller.report(ObjectTrackingEvent.FrameProblem(it)) }
        }
    }

    fun report(event: ObjectTrackingEvent) = controller.report(event)

    /** The object tracking screen is in front and the camera may be used. */
    fun onShown() = controller.activate()

    fun onHidden() = controller.deactivate()

    fun track() {
        alertGate.reset()
        trackRequested = true
        controller.track()
    }

    fun reset() = controller.reset()

    /** [code]'s mesh from [epoch] failed to bake; once per epoch it is fetched again. */
    fun onBakeFailed(epoch: Int, code: String, message: String) = controller.onBakeFailed(epoch, code, message)

    fun dismissBanner() {
        dismissJob?.cancel()
        _banner.value = null
    }

    fun dismissAlert() {
        _alert.value = null
    }

    fun updateConfig(next: ObjectTrackingConfig) {
        config.value = next
        store.save(next)
        controller.configure(next)
    }

    /** The in-scene outline toggle, which is the "Show outline" setting. */
    fun setOutlineVisible(visible: Boolean) = updateConfig(config.value.copy(showOutline = visible))

    /** Stops the session before the SDK is released; the status collector restarts it once signed in. */
    fun pauseForSdkChange() = controller.onSdkStatus(SdkStatus.Authenticating)

    private fun onError(error: MultiSetError) {
        if (config.value.showAlerts && alertGate.shouldShow(error)) _alert.value = error
    }

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

private const val MESH_CACHE_DIRECTORY = "object-meshes"
