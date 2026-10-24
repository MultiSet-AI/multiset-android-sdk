/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import com.multiset.sdk.model.AnchorTrust

sealed interface LocalizationPhase {
    data object Idle : LocalizationPhase
    data object WaitingForTracking : LocalizationPhase
    data object Localizing : LocalizationPhase
    data class Localized(val mapCode: String, val confidence: Float?, val trust: AnchorTrust) : LocalizationPhase
    data class Failed(val message: String, val transient: Boolean) : LocalizationPhase
}

sealed interface MeshStatus {
    data object Off : MeshStatus
    data object Loading : MeshStatus
    data object Shown : MeshStatus
    data object Hidden : MeshStatus
    data object NeedsPermission : MeshStatus
    data object NeedsFullSpace : MeshStatus
    data class Failed(val message: String) : MeshStatus
}

/** The scene can place nothing until a permission is granted or Full Space is entered. */
val MeshStatus.isBlocked: Boolean get() = this == MeshStatus.NeedsPermission || this == MeshStatus.NeedsFullSpace

enum class Notice { FRAME_PROBLEM, REJECTED_FIX, BACKGROUND_FAILURE }

data class LocalizationState(
    val phase: LocalizationPhase = LocalizationPhase.Idle,
    val mesh: MeshStatus = MeshStatus.Off,
    val notices: Map<Notice, String> = emptyMap(),
    val epoch: Int = 0,
    val fixes: Int = 0,
    val meshVisible: Boolean = true,
    /** The user asked to localize again while localized, and the answer hasn't come yet. */
    val relocalizing: Boolean = false,
    /** A localization request, the user's or a background one, is waiting for its answer. */
    val requestInFlight: Boolean = false,
) {
    /** Localizing on the user's behalf: the first localization, or a relocalize they asked for. */
    val isWorking: Boolean
        get() = phase == LocalizationPhase.Localizing || phase == LocalizationPhase.WaitingForTracking || relocalizing
}

sealed interface LocalizationEvent {
    data object WaitingForTracking : LocalizationEvent
    /** The user asked to localize (Localize or Relocalize). */
    data class Asked(val epoch: Int) : LocalizationEvent
    data class Requested(val epoch: Int) : LocalizationEvent
    data class Succeeded(val epoch: Int, val mapCode: String, val confidence: Float?, val trust: AnchorTrust) : LocalizationEvent
    data class Failed(val epoch: Int, val message: String, val transient: Boolean) : LocalizationEvent
    data class FalsePositive(val epoch: Int, val summary: String) : LocalizationEvent
    data class TrustChanged(val epoch: Int, val trust: AnchorTrust) : LocalizationEvent
    data object Reset : LocalizationEvent
    data object Stopped : LocalizationEvent
    data class MeshLoading(val epoch: Int) : LocalizationEvent
    data class MeshShown(val epoch: Int) : LocalizationEvent
    data class MeshFailed(val epoch: Int, val message: String) : LocalizationEvent
    data class MeshBlocked(val epoch: Int, val status: MeshStatus) : LocalizationEvent
    /** The scene can place the map again; [meshShown] is whether it still holds a loaded mesh. */
    data class MeshUnblocked(val epoch: Int, val meshShown: Boolean) : LocalizationEvent
    data class MeshVisibility(val visible: Boolean) : LocalizationEvent
    data class FrameProblem(val message: String?) : LocalizationEvent
}

/** Pure transition function; events tagged with an epoch older than the state's are stale and ignored. */
fun reduce(state: LocalizationState, event: LocalizationEvent): LocalizationState {
    val epoch = event.epochOrNull()
    if (epoch != null && epoch < state.epoch) return state
    val localized = state.phase is LocalizationPhase.Localized
    return when (event) {
        LocalizationEvent.WaitingForTracking ->
            if (state.phase == LocalizationPhase.Idle) state.copy(phase = LocalizationPhase.WaitingForTracking) else state
        is LocalizationEvent.Asked -> if (localized) state.copy(relocalizing = true) else state
        is LocalizationEvent.Requested ->
            if (localized) state.copy(requestInFlight = true) else state.copy(phase = LocalizationPhase.Localizing, requestInFlight = true)
        is LocalizationEvent.Succeeded -> state.copy(
            phase = LocalizationPhase.Localized(event.mapCode, event.confidence, event.trust),
            notices = emptyMap(),
            fixes = state.fixes + 1,
            relocalizing = false,
            requestInFlight = false,
        )
        is LocalizationEvent.Failed -> if (localized) {
            state.copy(notices = state.notices + (Notice.BACKGROUND_FAILURE to event.message), relocalizing = false, requestInFlight = false)
        } else {
            state.copy(phase = LocalizationPhase.Failed(event.message, event.transient), requestInFlight = false)
        }
        is LocalizationEvent.FalsePositive -> state.copy(
            notices = state.notices + (Notice.REJECTED_FIX to event.summary),
            relocalizing = false,
            requestInFlight = false,
        )
        is LocalizationEvent.TrustChanged -> when (val phase = state.phase) {
            is LocalizationPhase.Localized -> state.copy(phase = phase.copy(trust = event.trust))
            else -> state
        }
        LocalizationEvent.Reset -> LocalizationState(epoch = state.epoch + 1, meshVisible = state.meshVisible)
        LocalizationEvent.Stopped -> when (state.phase) {
            LocalizationPhase.Localizing, LocalizationPhase.WaitingForTracking ->
                state.copy(phase = LocalizationPhase.Idle, relocalizing = false, requestInFlight = false)
            else -> state.copy(relocalizing = false, requestInFlight = false)
        }
        is LocalizationEvent.MeshLoading -> state.copy(mesh = MeshStatus.Loading)
        // Only MeshUnblocked lifts a block; a late MeshShown must not claim a mesh nobody can place.
        is LocalizationEvent.MeshShown -> if (state.mesh.isBlocked) state else state.copy(mesh = state.shownMesh())
        is LocalizationEvent.MeshFailed -> state.copy(mesh = MeshStatus.Failed(event.message))
        is LocalizationEvent.MeshBlocked -> state.copy(mesh = event.status)
        is LocalizationEvent.MeshUnblocked -> when {
            !state.mesh.isBlocked -> state
            event.meshShown -> state.copy(mesh = state.shownMesh())
            else -> state.copy(mesh = MeshStatus.Off)
        }
        is LocalizationEvent.MeshVisibility -> when (state.mesh) {
            MeshStatus.Shown, MeshStatus.Hidden -> state.copy(mesh = if (event.visible) MeshStatus.Shown else MeshStatus.Hidden, meshVisible = event.visible)
            else -> state.copy(meshVisible = event.visible)
        }
        is LocalizationEvent.FrameProblem -> state.copy(
            notices = if (event.message == null) state.notices - Notice.FRAME_PROBLEM
            else state.notices + (Notice.FRAME_PROBLEM to event.message),
        )
    }
}

private fun LocalizationState.shownMesh() = if (meshVisible) MeshStatus.Shown else MeshStatus.Hidden

private fun LocalizationEvent.epochOrNull(): Int? = when (this) {
    is LocalizationEvent.Asked -> epoch
    is LocalizationEvent.Requested -> epoch
    is LocalizationEvent.Succeeded -> epoch
    is LocalizationEvent.Failed -> epoch
    is LocalizationEvent.FalsePositive -> epoch
    is LocalizationEvent.TrustChanged -> epoch
    is LocalizationEvent.MeshLoading -> epoch
    is LocalizationEvent.MeshShown -> epoch
    is LocalizationEvent.MeshFailed -> epoch
    is LocalizationEvent.MeshBlocked -> epoch
    is LocalizationEvent.MeshUnblocked -> epoch
    else -> null
}
