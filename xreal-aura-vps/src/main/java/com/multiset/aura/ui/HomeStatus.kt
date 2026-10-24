/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.ui

import com.multiset.aura.camera.CameraStatus
import com.multiset.aura.localization.LocalizationPhase
import com.multiset.aura.localization.LocalizationState
import com.multiset.aura.objecttracking.ObjectPhase
import com.multiset.aura.objecttracking.ObjectTrackingState
import com.multiset.aura.pose.TrackingStatus
import com.multiset.aura.sdk.SdkStatus

/** How urgent a flow screen's status is: the colour of its dot. */
enum class StatusTone { GOOD, BUSY, BAD, IDLE }

/** The one status a flow screen's pill shows: the most urgent problem, else the flow's state. */
sealed class HomeStatus(val tone: StatusTone) {
    data object NotConfigured : HomeStatus(StatusTone.BAD)
    data object SignInFailed : HomeStatus(StatusTone.BAD)
    data object SigningIn : HomeStatus(StatusTone.BUSY)
    data object CameraOff : HomeStatus(StatusTone.IDLE)
    data object CameraStarting : HomeStatus(StatusTone.BUSY)
    data object CameraUnavailable : HomeStatus(StatusTone.BAD)
    data object TrackingStarting : HomeStatus(StatusTone.BUSY)
    data object TrackingLost : HomeStatus(StatusTone.BUSY)
    data object TrackingUnavailable : HomeStatus(StatusTone.BAD)
    data object NotLocalized : HomeStatus(StatusTone.IDLE)
    data object Localizing : HomeStatus(StatusTone.BUSY)
    data object LocalizationFailed : HomeStatus(StatusTone.BAD)
    /** [refreshing]: a background request is checking the fix. */
    data class Localized(val confidence: Float?, val refreshing: Boolean = false) : HomeStatus(StatusTone.GOOD)
    data object ObjectsIdle : HomeStatus(StatusTone.IDLE)
    data object ObjectsSearching : HomeStatus(StatusTone.BUSY)
    /** [confidence] is the best of the found objects' latest; [refreshing] as in [Localized]. */
    data class ObjectsFound(val count: Int, val confidence: Double?, val refreshing: Boolean) : HomeStatus(StatusTone.GOOD)
    data object ObjectsFailed : HomeStatus(StatusTone.BAD)
}

/**
 * Sign-in, then the camera, then tracking: each makes the localization below it meaningless.
 * [previewEnabled] gates the camera rows because CameraHub reports Opening/Live only while a preview
 * is attached; a missing camera permission shows either way.
 */
fun mapStatus(
    sdk: SdkStatus,
    tracking: TrackingStatus,
    camera: CameraStatus,
    cameraGranted: Boolean,
    previewEnabled: Boolean,
    localization: LocalizationState,
): HomeStatus = deviceStatus(sdk, tracking, camera, cameraGranted, previewEnabled)
    ?: when (val phase = localization.phase) {
        LocalizationPhase.Idle -> HomeStatus.NotLocalized
        LocalizationPhase.WaitingForTracking, LocalizationPhase.Localizing -> HomeStatus.Localizing
        is LocalizationPhase.Localized ->
            if (localization.relocalizing) HomeStatus.Localizing else HomeStatus.Localized(phase.confidence, localization.requestInFlight)
        is LocalizationPhase.Failed -> HomeStatus.LocalizationFailed
    }

/** The same device rows as [mapStatus], then the object tracking. */
fun objectStatus(
    sdk: SdkStatus,
    tracking: TrackingStatus,
    camera: CameraStatus,
    cameraGranted: Boolean,
    previewEnabled: Boolean,
    objects: ObjectTrackingState,
): HomeStatus = deviceStatus(sdk, tracking, camera, cameraGranted, previewEnabled)
    ?: when (objects.phase) {
        ObjectPhase.Idle -> HomeStatus.ObjectsIdle
        ObjectPhase.WaitingForTracking, ObjectPhase.Searching -> HomeStatus.ObjectsSearching
        ObjectPhase.Found -> HomeStatus.ObjectsFound(
            objects.objects.size,
            objects.objects.values.mapNotNull { it.confidence }.maxOrNull(),
            objects.requestInFlight,
        )
        is ObjectPhase.Failed -> HomeStatus.ObjectsFailed
    }

private fun deviceStatus(
    sdk: SdkStatus,
    tracking: TrackingStatus,
    camera: CameraStatus,
    cameraGranted: Boolean,
    previewEnabled: Boolean,
): HomeStatus? = when {
    sdk is SdkStatus.NotConfigured -> HomeStatus.NotConfigured
    sdk is SdkStatus.AuthFailed -> HomeStatus.SignInFailed
    sdk == SdkStatus.Authenticating -> HomeStatus.SigningIn
    !cameraGranted -> HomeStatus.CameraOff
    previewEnabled && camera == CameraStatus.Off -> HomeStatus.CameraOff
    previewEnabled && camera is CameraStatus.Error -> HomeStatus.CameraUnavailable
    previewEnabled && camera == CameraStatus.Opening -> HomeStatus.CameraStarting
    tracking is TrackingStatus.NotTracking -> HomeStatus.TrackingLost
    tracking == TrackingStatus.Starting -> HomeStatus.TrackingStarting
    tracking != TrackingStatus.Tracking -> HomeStatus.TrackingUnavailable
    else -> null
}
