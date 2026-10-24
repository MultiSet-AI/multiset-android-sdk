/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.objecttracking

import com.multiset.aura.localization.Banner
import com.multiset.aura.localization.MeshStatus
import com.multiset.aura.localization.isBlocked

sealed interface ObjectPhase {
    data object Idle : ObjectPhase
    data object WaitingForTracking : ObjectPhase
    data object Searching : ObjectPhase
    /** At least one object has been tracked since the last reset. */
    data object Found : ObjectPhase
    data class Failed(val message: String, val transient: Boolean) : ObjectPhase
}

/** [fixes] counts this epoch's successful trackings of [code]; [confidence] is the latest one's. */
data class TrackedObject(val code: String, val confidence: Double?, val fixes: Int)

enum class ObjectNotice { FRAME_PROBLEM, BACKGROUND_FAILURE }

data class ObjectTrackingState(
    val phase: ObjectPhase = ObjectPhase.Idle,
    val objects: Map<String, TrackedObject> = emptyMap(),
    /** Each tracked object's outline mesh, by object code. */
    val meshes: Map<String, MeshStatus> = emptyMap(),
    val notices: Map<ObjectNotice, String> = emptyMap(),
    val epoch: Int = 0,
    /** A tracking request, the user's or a background one, is waiting for its answer. */
    val requestInFlight: Boolean = false,
    val outlineVisible: Boolean = true,
)

sealed interface ObjectTrackingEvent {
    data object WaitingForTracking : ObjectTrackingEvent
    /** The user asked to track (Track or Restart). */
    data class Asked(val epoch: Int) : ObjectTrackingEvent
    data class Requested(val epoch: Int) : ObjectTrackingEvent
    data class Succeeded(val epoch: Int, val code: String, val confidence: Double?) : ObjectTrackingEvent
    data class Failed(val epoch: Int, val message: String, val transient: Boolean) : ObjectTrackingEvent
    data object Reset : ObjectTrackingEvent
    data object Stopped : ObjectTrackingEvent
    data class MeshLoading(val epoch: Int, val code: String) : ObjectTrackingEvent
    data class MeshShown(val epoch: Int, val code: String) : ObjectTrackingEvent
    data class MeshFailed(val epoch: Int, val code: String, val message: String) : ObjectTrackingEvent
    /** The scene cannot show any outline, for every object alike. */
    data class MeshBlocked(val epoch: Int, val status: MeshStatus) : ObjectTrackingEvent
    /** The scene can place outlines again; [outlined] are the codes whose outline it still holds. */
    data class MeshUnblocked(val epoch: Int, val outlined: Set<String>) : ObjectTrackingEvent
    data class OutlineVisibility(val visible: Boolean) : ObjectTrackingEvent
    data class FrameProblem(val message: String?) : ObjectTrackingEvent
}

/** Pure transition function; events tagged with an epoch older than the state's are stale and ignored. */
fun reduce(state: ObjectTrackingState, event: ObjectTrackingEvent): ObjectTrackingState {
    val epoch = event.epochOrNull()
    if (epoch != null && epoch < state.epoch) return state
    val found = state.phase == ObjectPhase.Found
    return when (event) {
        ObjectTrackingEvent.WaitingForTracking ->
            if (state.phase == ObjectPhase.Idle) state.copy(phase = ObjectPhase.WaitingForTracking) else state
        // Requests wait out the capture delay; meanwhile show the new attempt, not the old failure.
        is ObjectTrackingEvent.Asked -> when (state.phase) {
            ObjectPhase.Idle, is ObjectPhase.Failed -> state.copy(phase = ObjectPhase.Searching)
            else -> state
        }
        is ObjectTrackingEvent.Requested ->
            if (found) state.copy(requestInFlight = true) else state.copy(phase = ObjectPhase.Searching, requestInFlight = true)
        is ObjectTrackingEvent.Succeeded -> {
            val fixes = (state.objects[event.code]?.fixes ?: 0) + 1
            state.copy(
                phase = ObjectPhase.Found,
                objects = state.objects + (event.code to TrackedObject(event.code, event.confidence, fixes)),
                notices = emptyMap(),
                requestInFlight = false,
            )
        }
        is ObjectTrackingEvent.Failed -> if (found) {
            state.copy(notices = state.notices + (ObjectNotice.BACKGROUND_FAILURE to event.message), requestInFlight = false)
        } else {
            state.copy(phase = ObjectPhase.Failed(event.message, event.transient), requestInFlight = false)
        }
        ObjectTrackingEvent.Reset -> ObjectTrackingState(epoch = state.epoch + 1, outlineVisible = state.outlineVisible)
        ObjectTrackingEvent.Stopped -> when (state.phase) {
            ObjectPhase.Searching, ObjectPhase.WaitingForTracking -> state.copy(phase = ObjectPhase.Idle, requestInFlight = false)
            else -> state.copy(requestInFlight = false)
        }
        is ObjectTrackingEvent.MeshLoading -> state.withMesh(event.code, MeshStatus.Loading)
        // Only MeshUnblocked lifts a block; a late MeshShown must not claim an outline nobody can place.
        is ObjectTrackingEvent.MeshShown ->
            if (state.meshes[event.code]?.isBlocked == true) state else state.withMesh(event.code, state.shownOutline())
        is ObjectTrackingEvent.MeshFailed -> state.withMesh(event.code, MeshStatus.Failed(event.message))
        is ObjectTrackingEvent.MeshBlocked -> state.copy(meshes = state.meshes.mapValues { event.status })
        is ObjectTrackingEvent.MeshUnblocked -> state.copy(
            meshes = state.meshes.mapValues { (code, mesh) ->
                when {
                    !mesh.isBlocked -> mesh
                    code in event.outlined -> state.shownOutline()
                    else -> MeshStatus.Off
                }
            },
        )
        is ObjectTrackingEvent.OutlineVisibility -> state.copy(
            meshes = state.meshes.mapValues { (_, mesh) ->
                when (mesh) {
                    MeshStatus.Shown, MeshStatus.Hidden -> if (event.visible) MeshStatus.Shown else MeshStatus.Hidden
                    else -> mesh
                }
            },
            outlineVisible = event.visible,
        )
        is ObjectTrackingEvent.FrameProblem -> state.copy(
            notices = if (event.message == null) state.notices - ObjectNotice.FRAME_PROBLEM
            else state.notices + (ObjectNotice.FRAME_PROBLEM to event.message),
        )
    }
}

/**
 * The banner a state change deserves, if any. An object tracked for the first time since a reset is
 * announced; a repeat fix or a background failure only when the wearer pressed Track for it.
 */
fun nextObjectBanner(previous: ObjectTrackingState, current: ObjectTrackingState, userRequested: Boolean): Banner? {
    val phase = current.phase
    val fixed = current.objects.values.firstOrNull { it.fixes > (previous.objects[it.code]?.fixes ?: 0) }
    val failedWhileFound = current.notices[ObjectNotice.BACKGROUND_FAILURE]
    return when {
        fixed != null && (fixed.code !in previous.objects || userRequested) ->
            Banner.Success(fixed.code, fixed.confidence?.toFloat())
        phase is ObjectPhase.Failed && phase != previous.phase -> Banner.Failure(phase.message)
        userRequested && failedWhileFound != null &&
            failedWhileFound != previous.notices[ObjectNotice.BACKGROUND_FAILURE] -> Banner.Failure(failedWhileFound)
        else -> null
    }
}

private fun ObjectTrackingState.withMesh(code: String, status: MeshStatus) = copy(meshes = meshes + (code to status))

private fun ObjectTrackingState.shownOutline() = if (outlineVisible) MeshStatus.Shown else MeshStatus.Hidden

private fun ObjectTrackingEvent.epochOrNull(): Int? = when (this) {
    is ObjectTrackingEvent.Asked -> epoch
    is ObjectTrackingEvent.Requested -> epoch
    is ObjectTrackingEvent.Succeeded -> epoch
    is ObjectTrackingEvent.Failed -> epoch
    is ObjectTrackingEvent.MeshLoading -> epoch
    is ObjectTrackingEvent.MeshShown -> epoch
    is ObjectTrackingEvent.MeshFailed -> epoch
    is ObjectTrackingEvent.MeshBlocked -> epoch
    is ObjectTrackingEvent.MeshUnblocked -> epoch
    else -> null
}
