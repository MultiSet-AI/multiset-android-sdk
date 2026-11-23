/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.multiset.sdk.model.AnchorPose

/** How the map anchor moves to each new fix. */
sealed class AlignmentSmoothing {

    /** Jump straight to each fix. */
    data object Off : AlignmentSmoothing()

    /**
     * Glide to each fix over this many seconds: linear in position, spherical in rotation. The first
     * placement, and the first fix after the alignment was cleared, still jump.
     */
    data class Interpolate(val seconds: Double) : AlignmentSmoothing()
}

/**
 * Moves the node the map content hangs under (the gizmo) to each fix, jumping or gliding. A child of the
 * scene, so its local pose is its pose in the AR session.
 */
class MapAnchor(private val node: Node, private val clockSeconds: () -> Double = { System.nanoTime() / 1e9 }) {

    var smoothing: AlignmentSmoothing = AlignmentSmoothing.Off
        set(value) {
            field = value
            when (value) {
                AlignmentSmoothing.Off -> {
                    smoother.target?.let(::apply)
                    smoother.clear()
                }
                is AlignmentSmoothing.Interpolate -> {
                    smoother.durationSeconds = maxOf(value.seconds, 0.0)
                    val placed = renderedAnchor
                    if (smoother.target == null && placed != null) smoother.setTarget(placed, clockSeconds(), snap = true)
                }
            }
        }

    var isPlaced = false
        private set

    private val smoother = AnchorSmoother(durationSeconds = 0.0)
    private var snapNext = false

    /** The anchor as the scene shows it; null until a fix has placed it. */
    val renderedAnchor: AnchorPose?
        get() = if (isPlaced) AnchorPose(node.localPosition.toVec3(), node.localRotation.toQuat()) else null

    fun moveTo(pose: AnchorPose) {
        when (smoothing) {
            AlignmentSmoothing.Off -> apply(pose)
            is AlignmentSmoothing.Interpolate -> {
                val now = clockSeconds()
                smoother.setTarget(pose, now, snap = !isPlaced || snapNext)
                onFrame(now)
            }
        }
        snapNext = false
        isPlaced = true
    }

    /** The alignment was cleared, so the room may have moved: the next fix jumps. */
    fun alignmentCleared() {
        snapNext = true
    }

    /** Call on every scene update. */
    fun onFrame(timeSeconds: Double = clockSeconds()) {
        if (smoothing == AlignmentSmoothing.Off) return
        smoother.pose(timeSeconds)?.let(::apply)
    }

    fun reset() {
        smoother.clear()
        node.localPosition = Vector3.zero()
        node.localRotation = Quaternion.identity()
        isPlaced = false
        snapNext = false
    }

    private fun apply(pose: AnchorPose) {
        node.localPosition = pose.position.toSceneform()
        node.localRotation = pose.rotation.toSceneform()
    }
}
