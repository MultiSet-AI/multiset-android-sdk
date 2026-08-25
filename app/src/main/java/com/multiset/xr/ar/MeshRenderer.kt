/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.RenderableInstance
import com.multiset.xr.R
import com.multiset.sdk.mesh.MapMeshResult
import java.io.File

/**
 * Renders a VPS map mesh onto a Sceneform [Node] using the radial-reveal material.
 *
 * Ported from com.multiset.sdk.internal.mesh.MeshRenderer, with these adaptations:
 *  - Consumes [MapMeshResult] (has meshBytes, not a file path); bytes are written to a
 *    persistent cache directory (context.filesDir/mesh_cache/{mapId}.glb) and re-used
 *    on subsequent calls for the same map.
 *  - MeshRevealAnimator is the app-local port (package com.multiset.xr.ar).
 */
class MeshRenderer(
    private val context: Context
) {
    companion object {
        private const val TAG = "MeshRenderer"
        private const val DEFAULT_ALPHA = 0.35f

        /** Persistent GLB cache — not evicted by the OS unlike cacheDir. */
        private const val CACHE_DIR = "mesh_cache"
    }

    private var meshNode: Node? = null
    private var parentNode: Node? = null
    private var transparentMaterial: Material? = null
    private var revealMaterial: Material? = null
    private var currentMapId: String? = null
    private var meshRevealAnimator: MeshRevealAnimator? = null
    private var cameraWorldPosition: Vector3? = null
    private var cameraPositionProvider: (() -> Vector3?)? = null

    var meshColor: Color = Color(0.3f, 0.1f, 0.55f, DEFAULT_ALPHA)

    fun setCameraPositionProvider(provider: (() -> Vector3?)?) {
        this.cameraPositionProvider = provider
    }

    /**
     * Render the map mesh described by [meshResult] as a child of [parentNode].
     *
     * If a mesh is already shown, this restarts the reveal animation from the current
     * camera position without re-loading the model.
     *
     * @param meshResult     bytes + pose from [com.multiset.sdk.mesh.MeshRepository].
     * @param parentNode     Sceneform parent (typically the gizmo node).
     * @param cameraWorldPosition  camera position at the moment of localization.
     * @param onComplete     called with true on success, false on failure.
     */
    fun renderMesh(
        meshResult: MapMeshResult,
        parentNode: Node,
        cameraWorldPosition: Vector3? = null,
        onComplete: (Boolean) -> Unit
    ) {
        if (isMeshRenderedForMap(meshResult.mapId)) {
            // Same map still on screen — replay the reveal from the current camera position
            // rather than re-loading. A *different* map falls through and loadGlbModel swaps it;
            // matching on meshNode alone left map A's geometry sitting at map B's origin.
            if (cameraWorldPosition != null) {
                this.cameraWorldPosition = cameraWorldPosition
                val node = meshNode
                val instance = node?.renderableInstance
                if (node != null && instance != null && revealMaterial != null) {
                    startRevealAnimation(node, instance)
                }
            }
            onComplete(true)
            return
        }

        this.parentNode = parentNode
        this.cameraWorldPosition = cameraWorldPosition

        // Resolve (or create) the persistent cache file for this map's GLB.
        val cacheFile = resolveCacheFile(meshResult.mapId, meshResult.meshBytes)
        if (cacheFile == null) {
            Log.e(TAG, "Failed to resolve cache file for mapId=${meshResult.mapId}")
            onComplete(false)
            return
        }

        // Try loading custom reveal material first, fall back to transparent.
        loadRevealMaterial { material ->
            if (material != null) {
                revealMaterial = material
                Log.d(TAG, "Custom reveal material loaded successfully")
                loadGlbModel(cacheFile, meshResult, parentNode, onComplete)
            } else {
                Log.w(TAG, "Custom reveal material unavailable, using fallback transparent material")
                createTransparentMaterial { fallbackMaterial ->
                    if (fallbackMaterial != null) {
                        transparentMaterial = fallbackMaterial
                    }
                    loadGlbModel(cacheFile, meshResult, parentNode, onComplete)
                }
            }
        }
    }

    // ── Material loading ──────────────────────────────────────────────────────

    private fun loadRevealMaterial(onComplete: (Material?) -> Unit) {
        try {
            Material.builder()
                .setSource(context, R.raw.radial_reveal_material)
                .build()
                .thenAccept { material ->
                    // Initialize with hidden state (progress = 0)
                    try {
                        material.setFloat4("revealParam", Color(0f, 0f, 0f, 0f))
                        material.setFloat("maxRadius", 1.0f)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set initial shader params: ${e.message}")
                    }
                    onComplete(material)
                }
                .exceptionally { throwable ->
                    Log.e(TAG, "Failed to load reveal material: ${throwable.message}")
                    onComplete(null)
                    null
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error building reveal material: ${e.message}")
            onComplete(null)
        }
    }

    private fun createTransparentMaterial(onComplete: (Material?) -> Unit) {
        MaterialFactory.makeTransparentWithColor(context, meshColor)
            .thenAccept { material ->
                try {
                    material.setFloat4(MaterialFactory.MATERIAL_COLOR, meshColor)
                } catch (e: Exception) {
                    // Ignore
                }
                onComplete(material)
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Failed to create transparent material: ${throwable.message}")
                onComplete(null)
                null
            }
    }

    // ── File cache ────────────────────────────────────────────────────────────

    /**
     * Returns the cache file for this mapId, writing bytes only when the file is absent.
     * Returns null if the directory cannot be created or the write fails.
     */
    private fun resolveCacheFile(mapId: String, meshBytes: ByteArray): File? {
        return try {
            val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
            val file = File(cacheDir, "$mapId.glb")
            if (!file.exists()) {
                file.outputStream().use { it.write(meshBytes) }
                Log.d(TAG, "GLB written to persistent cache: ${file.absolutePath}")
            } else {
                Log.d(TAG, "GLB already cached: ${file.absolutePath}")
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write GLB cache for mapId=$mapId: ${e.message}", e)
            null
        }
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun loadGlbModel(
        meshFile: File,
        meshResult: MapMeshResult,
        parentNode: Node,
        onComplete: (Boolean) -> Unit
    ) {
        ModelRenderable.builder()
            .setSource(context, Uri.fromFile(meshFile))
            .setIsFilamentGltf(true)
            .setRegistryId(meshResult.mapId)
            .build()
            .thenAccept { renderable: ModelRenderable ->
                removeMesh()

                currentMapId = meshResult.mapId

                val node = Node()
                meshNode = node

                node.setParent(parentNode)

                val renderableInstance: RenderableInstance = node.setRenderable(renderable)

                // Apply material
                if (revealMaterial != null) {
                    applyRevealMaterialToInstance(renderableInstance)
                } else {
                    applyTransparentMaterialToInstance(renderableInstance)
                }

                // Set position/rotation BEFORE starting animation
                // (animator reads node.worldPosition to compute reveal center)
                node.localPosition = Vector3(
                    meshResult.localPosition[0],
                    meshResult.localPosition[1],
                    meshResult.localPosition[2]
                )

                node.localRotation = Quaternion(
                    meshResult.localRotation[0],
                    meshResult.localRotation[1],
                    meshResult.localRotation[2],
                    meshResult.localRotation[3]
                )

                node.localScale = Vector3.one()

                // Start reveal animation AFTER position/rotation are set
                if (revealMaterial != null) {
                    startRevealAnimation(node, renderableInstance)
                }

                onComplete(true)
            }
            .exceptionally { throwable: Throwable ->
                Log.e(TAG, "Error loading GLB mesh: ${throwable.message}", throwable)
                onComplete(false)
                null
            }
    }

    // ── Material application ──────────────────────────────────────────────────

    private fun applyRevealMaterialToInstance(renderableInstance: RenderableInstance) {
        val material = revealMaterial ?: return

        try {
            val materialCount = renderableInstance.materialsCount
            if (materialCount > 0) {
                for (i in 0 until materialCount) {
                    try {
                        renderableInstance.setMaterial(i, material)
                    } catch (e: IndexOutOfBoundsException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set reveal material at index $i: ${e.message}")
                    }
                }
            } else {
                renderableInstance.setMaterial(material)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying reveal material: ${e.message}", e)
        }
    }

    private fun applyTransparentMaterialToInstance(renderableInstance: RenderableInstance) {
        val material = transparentMaterial ?: return

        try {
            val materialCount = renderableInstance.materialsCount

            if (materialCount > 0) {
                var successCount = 0
                for (i in 0 until materialCount) {
                    try {
                        renderableInstance.setMaterial(i, material)
                        successCount++

                        try {
                            val appliedMaterial = renderableInstance.getMaterial(i)
                            appliedMaterial.setFloat4(MaterialFactory.MATERIAL_COLOR, meshColor)
                            appliedMaterial.setFloat(MaterialFactory.MATERIAL_METALLIC, 0.0f)
                            appliedMaterial.setFloat(MaterialFactory.MATERIAL_ROUGHNESS, 0.7f)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    } catch (e: IndexOutOfBoundsException) {
                        break
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                if (successCount == 0) {
                    try {
                        renderableInstance.setMaterial(material)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            } else {
                renderableInstance.setMaterial(material)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying transparent material to instance: ${e.message}", e)
        }
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private fun startRevealAnimation(node: Node, renderableInstance: RenderableInstance) {
        val camPos = cameraWorldPosition ?: return

        meshRevealAnimator?.stop()
        val animator = MeshRevealAnimator().apply {
            loop = true
            delayBetweenLoops = 20.0f
        }
        meshRevealAnimator = animator
        animator.start(node, renderableInstance, camPos, cameraPositionProvider)
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    fun setMeshVisible(visible: Boolean) {
        meshNode?.isEnabled = visible
    }

    fun removeMesh() {
        meshRevealAnimator?.stop()
        meshRevealAnimator = null
        meshNode?.let { node ->
            node.setParent(null)
            node.renderable = null
        }
        meshNode = null
        currentMapId = null
    }

    fun hasMesh(): Boolean = meshNode != null

    fun updateMeshPosition(position: Vector3) {
        meshNode?.localPosition = position
    }

    fun updateMeshRotation(rotation: Quaternion) {
        meshNode?.localRotation = rotation
    }

    fun setMeshColor(r: Float, g: Float, b: Float, alpha: Float) {
        meshColor = Color(r, g, b, alpha)
        createTransparentMaterial { material ->
            if (material != null) {
                transparentMaterial = material
                meshNode?.renderableInstance?.let { instance ->
                    applyTransparentMaterialToInstance(instance)
                }
            }
        }
    }

    fun forceRerender() {
        removeMesh()
    }

    fun isMeshRenderedForMap(mapId: String): Boolean {
        return meshNode != null && currentMapId == mapId
    }
}
