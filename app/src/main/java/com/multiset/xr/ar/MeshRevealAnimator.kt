/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import android.util.Log
import android.view.Choreographer
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.collision.Box
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.RenderableInstance
import com.multiset.sdk.math.PoseMath
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Drives the radial reveal animation on mesh renderables.
 * Uses Choreographer for 60fps updates, matching the Unity ShaderProgressAnimator.
 *
 * Shader uniforms:
 *   revealParam (float4): xyz = center world-space, w = progress (0..1)
 *   maxRadius (float): maximum reveal radius for normalized distance calculation
 *
 * Ported from com.multiset.sdk.internal.mesh.MeshRevealAnimator, with
 * Util matrix calls replaced by PoseMath + SceneformMath adapters.
 */
class MeshRevealAnimator {

    companion object {
        private const val TAG = "MeshRevealAnimator"
    }

    var loop: Boolean = true
    var delayBetweenLoops: Float = 20.0f
    var radiusPadding: Float = 5.0f

    private val smallRadius = 20.0f
    private val smallRadiusDuration = 10.0f
    private val largeRadius = 200.0f
    private val largeRadiusDuration = 35.0f

    private var startTimeNanos: Long = 0L
    private var animationDuration: Float = 5.0f
    private var maxRadius: Float = 10.0f
    private var centerWorldSpace = floatArrayOf(0f, 0f, 0f)
    private var centerModelSpace = floatArrayOf(0f, 0f, 0f)
    private var meshNode: Node? = null
    private var renderableInstance: RenderableInstance? = null
    private var isAnimating = false
    private var isPaused = false
    private var pauseEndTimeNanos: Long = 0L
    private var cameraPositionProvider: (() -> Vector3?)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isAnimating) {
                updateAnimation(frameTimeNanos)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /**
     * Start the radial reveal animation from the camera's world position.
     */
    fun start(
        meshNode: Node,
        renderableInstance: RenderableInstance,
        cameraWorldPosition: Vector3,
        cameraPositionProvider: (() -> Vector3?)?
    ) {
        this.meshNode = meshNode
        this.renderableInstance = renderableInstance
        this.cameraPositionProvider = cameraPositionProvider

        updateCenter(cameraWorldPosition)
        maxRadius = calculateMaxRadius(meshNode, centerModelSpace)
        animationDuration = calculateDuration(maxRadius)

        // Start hidden (progress = 0)
        updateShaderUniforms(0f)

        startTimeNanos = System.nanoTime()
        isAnimating = true
        isPaused = false
        Choreographer.getInstance().postFrameCallback(frameCallback)

        Log.d(TAG, "Started reveal (maxRadius=$maxRadius, duration=${animationDuration}s, " +
            "centerModel=[${centerModelSpace[0]}, ${centerModelSpace[1]}, ${centerModelSpace[2]}])")
    }

    fun stop() {
        isAnimating = false
        isPaused = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        meshNode = null
        renderableInstance = null
        cameraPositionProvider = null
    }

    /**
     * Store both world-space (for shader) and model-space (for radius calculation) centers.
     *
     * Replaces Util.createMatrixFromQuaternion / createTransformMatrix / invertMatrix
     * with PoseMath equivalents, using SceneformMath adapters (toQuat / toVec3).
     */
    private fun updateCenter(worldPosition: Vector3) {
        centerWorldSpace = floatArrayOf(worldPosition.x, worldPosition.y, worldPosition.z)

        val node = meshNode ?: return

        val nodeWorldPos = node.worldPosition
        val nodeWorldRot = node.worldRotation

        // PoseMath expects Quat / Vec3; use SceneformMath extension functions to adapt.
        val rotMatrix = PoseMath.createMatrixFromQuaternion(nodeWorldRot.toQuat())
        val worldTransform = PoseMath.createTransformMatrix(rotMatrix, nodeWorldPos.toVec3())
        val inverseTransform = PoseMath.invertMatrix(worldTransform)

        val wx = worldPosition.x
        val wy = worldPosition.y
        val wz = worldPosition.z
        val modelX = inverseTransform[0][0] * wx + inverseTransform[0][1] * wy + inverseTransform[0][2] * wz + inverseTransform[0][3]
        val modelY = inverseTransform[1][0] * wx + inverseTransform[1][1] * wy + inverseTransform[1][2] * wz + inverseTransform[1][3]
        val modelZ = inverseTransform[2][0] * wx + inverseTransform[2][1] * wy + inverseTransform[2][2] * wz + inverseTransform[2][3]

        centerModelSpace = floatArrayOf(modelX, modelY, modelZ)
    }

    private fun updateCenterFromCamera() {
        val newCameraPos = cameraPositionProvider?.invoke() ?: return
        updateCenter(newCameraPos)

        meshNode?.let {
            maxRadius = calculateMaxRadius(it, centerModelSpace)
            animationDuration = calculateDuration(maxRadius)
        }
    }

    private fun updateAnimation(frameTimeNanos: Long) {
        val nowNanos = System.nanoTime()

        if (isPaused) {
            // Keep mesh fully revealed during pause
            updateShaderUniforms(1.0f)
            if (nowNanos >= pauseEndTimeNanos) {
                updateCenterFromCamera()
                isPaused = false
                startTimeNanos = nowNanos
                updateShaderUniforms(0f)
            }
            return
        }

        // Linear progress matching Unity: progress = elapsed / duration
        val elapsedSeconds = (nowNanos - startTimeNanos) / 1_000_000_000f
        val progress = minOf(elapsedSeconds / animationDuration, 1.0f)

        updateShaderUniforms(progress)

        if (progress >= 1.0f) {
            if (loop) {
                if (delayBetweenLoops > 0f) {
                    isPaused = true
                    pauseEndTimeNanos = nowNanos + (delayBetweenLoops * 1_000_000_000L).toLong()
                } else {
                    updateCenterFromCamera()
                    startTimeNanos = nowNanos
                    updateShaderUniforms(0f)
                }
            } else {
                isAnimating = false
            }
        }
    }

    /**
     * Update shader uniforms:
     *   revealParam (float4): xyz = center in model/object space, w = progress (0..1)
     *   maxRadius (float): for normalized distance in shader (model space)
     *
     * Both center and maxRadius are in model space to match the shader which uses
     * model-space vertex positions (via variable0) for all calculations.
     */
    private fun updateShaderUniforms(progress: Float) {
        val instance = renderableInstance ?: return

        try {
            val materialCount = instance.materialsCount
            for (i in 0 until materialCount) {
                try {
                    val material = instance.getMaterial(i)
                    material.setFloat4(
                        "revealParam",
                        Color(
                            centerModelSpace[0],
                            centerModelSpace[1],
                            centerModelSpace[2],
                            progress
                        )
                    )
                    material.setFloat("maxRadius", maxRadius)
                } catch (e: Exception) {
                    // Slot may not have the custom parameter
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update shader uniforms: ${e.message}")
        }
    }

    /**
     * Calculate max distance from center to any corner of any mesh bounding box.
     */
    private fun calculateMaxRadius(node: Node, center: FloatArray): Float {
        var maxDist = 0f

        fun processNode(n: Node) {
            n.renderable?.let { renderable ->
                val shape = renderable.collisionShape
                if (shape is Box) {
                    val size = shape.size
                    val boxCenter = shape.center

                    val signs = arrayOf(
                        floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f),
                        floatArrayOf(-1f, 1f, -1f), floatArrayOf(1f, 1f, -1f),
                        floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f),
                        floatArrayOf(-1f, 1f, 1f), floatArrayOf(1f, 1f, 1f)
                    )

                    for (s in signs) {
                        val cx = boxCenter.x + size.x * 0.5f * s[0]
                        val cy = boxCenter.y + size.y * 0.5f * s[1]
                        val cz = boxCenter.z + size.z * 0.5f * s[2]
                        val dx = cx - center[0]
                        val dy = cy - center[1]
                        val dz = cz - center[2]
                        val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                        maxDist = maxOf(maxDist, dist)
                    }
                }
            }
            for (child in n.children) {
                processNode(child)
            }
        }

        processNode(node)
        return maxDist + radiusPadding
    }

    /**
     * Power-curve interpolation for animation duration based on radius.
     * Small meshes (20 units) -> 10s, large meshes (200 units) -> 35s.
     */
    private fun calculateDuration(radius: Float): Float {
        val r = maxOf(radius, 1.0f)
        val exponent = ln(largeRadiusDuration / smallRadiusDuration) / ln(largeRadius / smallRadius)
        val coefficient = smallRadiusDuration / smallRadius.pow(exponent)
        return coefficient * r.pow(exponent)
    }
}
