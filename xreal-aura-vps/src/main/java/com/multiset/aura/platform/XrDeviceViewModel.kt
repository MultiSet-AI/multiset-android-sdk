/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.platform

import android.app.Application
import android.hardware.camera2.CameraManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multiset.aura.camera.CameraHub
import com.multiset.aura.frame.AuraFrameSource
import com.multiset.aura.pose.HeadPoseTracker
import com.multiset.aura.pose.TrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Activity-scoped device state shared by every screen: the probe, the head tracker, the Camera2 hub
 * and the one frame source both flows feed the SDK from.
 */
class XrDeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraManager = application.getSystemService(CameraManager::class.java)

    val headPose = HeadPoseTracker()

    val camera = CameraHub(application, cameraManager, viewModelScope)

    @Volatile
    private var displayRotation = 0

    val frameSource = AuraFrameSource(camera, headPose) { displayRotation }

    val tracking: StateFlow<TrackingStatus> = headPose.status

    private val _device = MutableStateFlow<XrDeviceInfo?>(null)
    val device: StateFlow<XrDeviceInfo?> = _device.asStateFlow()

    private val _permissionGrants = MutableStateFlow(0)

    /** Bumped whenever an XR permission is granted, so effects that need one restart. */
    val permissionGrants: StateFlow<Int> = _permissionGrants.asStateFlow()

    private val _scenePermissionMissing = MutableStateFlow(isScenePermissionMissing())

    /** Anchoring content in the room still needs SCENE_UNDERSTANDING_COARSE; re-read on every grant. */
    val scenePermissionMissing: StateFlow<Boolean> = _scenePermissionMissing.asStateFlow()

    fun onDeviceProbed(info: XrDeviceInfo) {
        _device.value = info
    }

    fun onPermissionGranted() {
        _scenePermissionMissing.value = isScenePermissionMissing()
        _permissionGrants.update { it + 1 }
    }

    /** Clockwise degrees the display is turned from natural; each frame reads the latest. */
    fun setDisplayRotation(degrees: Int) {
        displayRotation = degrees
    }

    /** Runs one [AuraFrameSource.acquire]; the outcome shows in its lastFrame and lastProblem. */
    fun captureTestFrame() {
        viewModelScope.launch { frameSource.acquire()?.bitmap?.recycle() }
    }

    private fun isScenePermissionMissing() =
        getApplication<Application>().isXrPermissionMissing(XrPermissions.SCENE_UNDERSTANDING_COARSE)

    override fun onCleared() {
        camera.release()
    }
}
