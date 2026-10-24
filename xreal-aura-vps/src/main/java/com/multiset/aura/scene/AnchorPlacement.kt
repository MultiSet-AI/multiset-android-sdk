/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene

import android.content.Context
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateResourcesExhausted
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.AnchorCreateTrackingUnavailable
import androidx.xr.arcore.AnchorRuntimeFailureException
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.scene
import com.multiset.aura.localization.MeshStatus
import com.multiset.aura.localization.needsReanchor
import com.multiset.aura.platform.XrPermissions
import com.multiset.aura.platform.isXrPermissionMissing

/**
 * Anchors [root] in the room at the poses it is given, moving it onto a new anchor only once that
 * anchor is placed. [onBlocked] reports a missing permission; [onFailed] an anchor that could not be
 * created or placed. Call everything from the main thread.
 */
class AnchorPlacement(
    private val context: Context,
    private val session: Session,
    private val root: Entity,
    private val onBlocked: (MeshStatus) -> Unit,
    private val onFailed: (reason: String) -> Unit,
) {

    private var anchor: Anchor? = null
    private var pending: PendingAnchor? = null

    /** The pose [root] is anchored at; null until an anchor is placed. */
    var placedPose: Pose? = null
        private set

    val isAnchored: Boolean get() = anchor != null

    /** Where [root] is anchored or about to be: the pending anchor's pose, else [placedPose]. */
    val targetPose: Pose? get() = pending?.pose ?: placedPose

    private class PendingAnchor(val anchor: Anchor, val space: AnchorSpace, val pose: Pose)

    /** Starts anchoring at [pose]; [root] keeps its current anchor until the new one is placed. */
    fun place(pose: Pose) {
        if (!needsReanchor(targetPose, pose)) return
        if (context.isXrPermissionMissing(XrPermissions.SCENE_UNDERSTANDING_COARSE)) {
            onBlocked(MeshStatus.NeedsPermission)
            return
        }
        val result = try {
            Anchor.create(session, pose)
        } catch (e: SecurityException) {
            onBlocked(MeshStatus.NeedsPermission)
            return
        } catch (e: AnchorRuntimeFailureException) {
            onFailed("the XR runtime could not create an anchor")
            return
        }
        val created = when (result) {
            is AnchorCreateSuccess -> result.anchor
            is AnchorCreateTrackingUnavailable -> return onFailed("tracking is unavailable")
            is AnchorCreateResourcesExhausted -> return onFailed("the device is out of anchors")
            else -> return onFailed(result.toString())
        }
        // Made before the old pending anchor goes, so a failure leaves that hand-off running.
        val space = try {
            AnchorSpace.create(session, created)
        } catch (e: Exception) {
            created.detach()
            return onFailed("the XR runtime could not create an anchor space (${e.message ?: e.javaClass.simpleName})")
        }
        pending?.anchor?.detach()
        pending = PendingAnchor(created, space, pose)
    }

    /** Completes an anchor hand-off; call once per frame. */
    fun tick() {
        pending?.let(::settle)
    }

    /** Drops both anchors and sends [root], disabled, back to the activity space. */
    fun clear() {
        pending?.anchor?.detach()
        pending = null
        root.setEnabled(false)
        root.parent = session.scene.activitySpace
        // AnchorSpace is a SpaceEntity, whose parent cannot be set; detaching the anchor is enough.
        anchor?.detach()
        anchor = null
        placedPose = null
    }

    // A new AnchorSpace starts UNANCHORED; moving the root onto it before it is placed makes it blink.
    private fun settle(next: PendingAnchor) {
        when (next.space.state) {
            AnchorSpace.State.ANCHORED -> {
                root.parent = next.space
                root.setPose(Pose())
                root.setEnabled(true)
                anchor?.detach()
                anchor = next.anchor
                placedPose = next.pose
                pending = null
            }
            AnchorSpace.State.TIMED_OUT -> {
                next.anchor.detach()
                pending = null
                onFailed("the anchor could not be placed in time")
            }
            else -> Unit
        }
    }
}
