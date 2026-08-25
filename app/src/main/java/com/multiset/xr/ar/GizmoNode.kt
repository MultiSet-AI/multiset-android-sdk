/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import android.content.Context
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory

class GizmoNode(private val context: Context) : Node() {

    init {
        createGizmo()
    }

    private fun createGizmo() {
        // Create X axis (Red) - pointing right
        val xAxis = Node()
        xAxis.localPosition = Vector3(0.25f, 0f, 0f)
        xAxis.setParent(this)

        // Create Y axis (Green) - pointing up
        val yAxis = Node()
        yAxis.localPosition = Vector3(0f, 0.25f, 0f)
        yAxis.setParent(this)

        // Create Z axis (Blue) - pointing forward
        val zAxis = Node()
        zAxis.localPosition = Vector3(0f, 0f, 0.25f)
        zAxis.setParent(this)

        // Create center sphere (Black)
        val centerSphere = Node()
        centerSphere.localPosition = Vector3.zero()
        centerSphere.setParent(this)

        // Setup materials immediately
        setupMaterials()
    }

    private fun setupMaterials() {
        // X axis - Red cube
        MaterialFactory.makeOpaqueWithColor(context, com.google.ar.sceneform.rendering.Color(1f, 0f, 0f))
            .thenAccept { material ->
                if (children.size > 0) {
                    children[0].renderable = ShapeFactory.makeCube(
                        Vector3(0.5f, 0.05f, 0.05f),
                        Vector3.zero(),
                        material
                    )
                }
            }

        // Y axis - Green cube
        MaterialFactory.makeOpaqueWithColor(context, com.google.ar.sceneform.rendering.Color(0f, 1f, 0f))
            .thenAccept { material ->
                if (children.size > 1) {
                    children[1].renderable = ShapeFactory.makeCube(
                        Vector3(0.05f, 0.5f, 0.05f),
                        Vector3.zero(),
                        material
                    )
                }
            }

        // Z axis - Blue cube
        MaterialFactory.makeOpaqueWithColor(context, com.google.ar.sceneform.rendering.Color(0f, 0f, 1f))
            .thenAccept { material ->
                if (children.size > 2) {
                    children[2].renderable = ShapeFactory.makeCube(
                        Vector3(0.05f, 0.05f, 0.5f),
                        Vector3.zero(),
                        material
                    )
                }
            }

        // Center sphere - Black
        MaterialFactory.makeOpaqueWithColor(context, com.google.ar.sceneform.rendering.Color(0f, 0f, 0f))
            .thenAccept { material ->
                if (children.size > 3) {
                    children[3].renderable = ShapeFactory.makeSphere(
                        0.05f,
                        Vector3.zero(),
                        material
                    )
                }
            }
    }

    fun show() {
        isEnabled = true
    }

    fun hide() {
        isEnabled = false
    }
}
