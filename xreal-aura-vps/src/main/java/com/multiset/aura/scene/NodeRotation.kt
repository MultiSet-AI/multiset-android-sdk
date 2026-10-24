/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene

import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.abs

/** A glTF node's two transform setters in SceneCore; each rebuilds the node's whole transform. */
interface NodeSetters {
    /** `localPose` with no translation: keeps the scale. */
    fun setRotation(rotation: Quaternion)

    /** `localScale`: hands the node's current rotation back to the native setter. */
    fun setScale(scale: Vector3)
}

/**
 * How SceneCore stores a rotation given to a glTF node. rc01's `ImpressApiImpl` passes it to native
 * code as (x, y, z, w), which reads it as (w, x, y, z): every `localScale` turned a node by 180°, so
 * the map mesh's reveal left its shells turned about the map origin while pins stood right.
 */
enum class NodeRotation {
    /** Rotations are stored as given. */
    CORRECT,

    /** Each rotation is stored as (y, z, w, x) of the one given. */
    SWAPPED,

    /** Neither; node transforms are left alone. */
    UNKNOWN;

    /** What to hand the setter so that it stores [wanted]. */
    fun toPass(wanted: Quaternion): Quaternion = when (this) {
        SWAPPED -> Quaternion(wanted.w, wanted.x, wanted.y, wanted.z)
        else -> wanted
    }

    /** Shrinks or hides [node] to [scale], leaving it unrotated. */
    fun shrink(node: NodeSetters, scale: Vector3) {
        node.setScale(scale)
        if (this == SWAPPED) node.setRotation(toPass(IDENTITY))
    }

    /** Grows [node] to [scale] unrotated; it is only turned while still shrunk. */
    fun grow(node: NodeSetters, scale: Vector3) {
        // The scale setter hands this back swapped, which stores the identity.
        if (this == SWAPPED) node.setRotation(toPass(PRIMER))
        node.setScale(scale)
    }

    companion object {
        private val IDENTITY = Quaternion(0f, 0f, 0f, 1f)
        private val PRIMER = Quaternion(1f, 0f, 0f, 0f)
        private val SWAPPED_IDENTITY = Quaternion(0f, 0f, 1f, 0f)

        /** From the rotation read back after setting the identity on a fresh node. */
        fun detect(readBack: Quaternion): NodeRotation = when {
            same(readBack, IDENTITY) -> CORRECT
            same(readBack, SWAPPED_IDENTITY) -> SWAPPED
            else -> UNKNOWN
        }

        private fun same(a: Quaternion, b: Quaternion) = abs(a.dot(b)) > 0.999f
    }
}
