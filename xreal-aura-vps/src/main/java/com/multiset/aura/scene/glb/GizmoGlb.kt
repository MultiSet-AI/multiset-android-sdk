/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene.glb

/** One unlit gizmo part: xyz [positions], triangle [indices] and an RGBA [color]. */
class GizmoShape(val positions: FloatArray, val indices: ShortArray, val color: FloatArray)

/**
 * Builds the map-origin gizmo as a GLB, matching the MultiSet sample's `GizmoNode`: red, green and
 * blue bars along +X, +Y and +Z and a sphere at the origin. The sample's sphere is black, which an
 * additive see-through display cannot show, so it is white here.
 */
object GizmoGlb {

    /** The X, Y and Z bars, then the centre sphere, in the order the GLB writes them. */
    fun shapes(axisLength: Float = 0.5f, thickness: Float = 0.05f, centreRadius: Float = 0.05f): List<GizmoShape> {
        val h = thickness / 2
        return listOf(
            ShapeGlb.box(floatArrayOf(0f, -h, -h), floatArrayOf(axisLength, h, h), floatArrayOf(1f, 0f, 0f, 1f)),
            ShapeGlb.box(floatArrayOf(-h, 0f, -h), floatArrayOf(h, axisLength, h), floatArrayOf(0f, 1f, 0f, 1f)),
            ShapeGlb.box(floatArrayOf(-h, -h, 0f), floatArrayOf(h, h, axisLength), floatArrayOf(0f, 0f, 1f, 1f)),
            ShapeGlb.sphere(centreRadius, floatArrayOf(1f, 1f, 1f, 1f)),
        )
    }

    fun build(axisLength: Float = 0.5f, thickness: Float = 0.05f, centreRadius: Float = 0.05f): ByteArray =
        ShapeGlb.write(shapes(axisLength, thickness, centreRadius))
}
