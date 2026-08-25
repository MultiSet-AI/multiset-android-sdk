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
import android.view.Choreographer
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.RenderableInstance
import com.multiset.xr.R
import com.multiset.sdk.mesh.MeshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fetches GLB mesh data via [MeshRepository] and renders it onto a Sceneform [Node].
 *
 * Rendering simplifications vs ObjectMeshHandler reference:
 * - Outline animation (Choreographer-driven animTime update) is retained.
 * - radial_reveal material / reveal animation is omitted (not referenced in public API surface).
 * - No persistent disk cache beyond context.cacheDir (temp files; OS may evict between sessions).
 * - [MeshRepository] owns the bearer token: it fetches a current one per request and refreshes it.
 */
class ObjectMeshRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val meshRepository: MeshRepository,
) {
    companion object {
        private const val TAG = "ObjectMeshRenderer"
        // Purple matching iOS/Unity OutlineMat: #511A80, alpha ~150/255
        private val OUTLINE_COLOR = Color(0.3195f, 0.1014f, 0.5f, 0.5882f)
    }

    private val rendererJob = SupervisorJob(scope.coroutineContext[Job])
    private val rendererScope = CoroutineScope(scope.coroutineContext + rendererJob)

    private val meshNodes = mutableMapOf<String, Node>()
    private val parentNodes = mutableMapOf<String, Node>()
    private val fetchedObjectCodes = mutableSetOf<String>()

    private var outlineMaterial: Material? = null
    private var outlineAnimator: OutlineAnimator? = null
    private var materialLoaded = false

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Loads the outline material from raw resources. Call once before [fetchAndRender].
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun loadOutlineMaterial() {
        if (materialLoaded) return
        materialLoaded = true

        try {
            Material.builder()
                .setSource(context, R.raw.outline_only_material)
                .build()
                .thenAccept { material ->
                    try {
                        material.setFloat4("outlineColor", OUTLINE_COLOR)
                        material.setFloat("outlineWidth", 0.006f)
                        material.setFloat("glowIntensity", 3.0f)
                        material.setFloat("animTime", 0f)
                        material.setFloat("sparkFrequency", 8.0f)
                        material.setFloat("sparkIntensity", 3.0f)
                        material.setFloat("rimPower", 5.0f)
                        material.setFloat("edgeCutoff", 0.25f)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set outline params: ${e.message}")
                    }
                    outlineMaterial = material
                    Log.d(TAG, "Outline material loaded")
                    startOutlineAnimation()
                }
                .exceptionally { throwable ->
                    Log.e(TAG, "Failed to load outline material: ${throwable.message}")
                    null
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error building outline material: ${e.message}")
        }
    }

    /**
     * Fetches mesh metadata + GLB bytes via [MeshRepository], writes to a temp file in
     * [Context.getCacheDir], then loads the GLB via [ModelRenderable] and attaches the
     * rendered node to [parentNode].
     *
     * Idempotent per [objectCode] — subsequent calls for the same code are ignored.
     */
    fun fetchAndRender(objectCode: String, parentNode: Node) {
        if (fetchedObjectCodes.contains(objectCode)) return
        fetchedObjectCodes.add(objectCode)
        parentNodes[objectCode] = parentNode

        rendererScope.launch(Dispatchers.IO) {
            try {
                // Step 1: fetch metadata to get the file URL
                val metadata = meshRepository.fetchObjectMeshMetadata(objectCode)
                val fileUrl = metadata.fileUrl
                if (fileUrl.isNullOrBlank()) {
                    Log.e(TAG, "No mesh URL for object $objectCode")
                    return@launch
                }

                // Step 2: download GLB bytes
                val glbBytes = meshRepository.downloadMesh(fileUrl)

                // Step 3: write to persistent file in filesDir/object_mesh_cache
                val objectCacheDir = File(context.filesDir, "object_mesh_cache").apply { mkdirs() }
                val cacheFile = File(objectCacheDir, "mesh_${objectCode}.glb")
                cacheFile.outputStream().use { it.write(glbBytes) }
                Log.d(TAG, "GLB cached at ${cacheFile.absolutePath}")

                // Step 4: load in Sceneform on Main thread
                withContext(Dispatchers.Main) {
                    loadMeshInScene(cacheFile, objectCode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch/render mesh for $objectCode: ${e.message}", e)
            }
        }
    }

    /**
     * Cancels all pending coroutines, stops the outline animator, and detaches / clears
     * all mesh nodes. Call when the AR session ends.
     */
    fun release() {
        rendererJob.cancel()
        outlineAnimator?.stop()
        outlineAnimator = null
        meshNodes.values.forEach { node ->
            node.setParent(null)
            node.renderable = null
        }
        meshNodes.clear()
        parentNodes.clear()
        fetchedObjectCodes.clear()
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private fun loadMeshInScene(file: File, objectCode: String) {
        if (meshNodes.containsKey(objectCode)) {
            Log.w(TAG, "Mesh for $objectCode already loaded")
            return
        }

        ModelRenderable.builder()
            .setSource(context, Uri.fromFile(file))
            .setIsFilamentGltf(true)
            .setRegistryId(objectCode)
            .build()
            .thenAccept { renderable ->
                val meshNode = Node().apply { name = objectCode }

                parentNodes[objectCode]?.let { meshNode.setParent(it) }

                val renderableInstance = meshNode.setRenderable(renderable)

                meshNode.localPosition = Vector3.zero()
                meshNode.localRotation = Quaternion.identity()
                meshNode.localScale = Vector3.one()

                applyOutlineMaterial(renderableInstance)

                meshNodes[objectCode] = meshNode
                Log.d(TAG, "Mesh loaded and rendered for object: $objectCode")
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Failed to load GLB: ${throwable.message}")
                null
            }
    }

    private fun applyOutlineMaterial(renderableInstance: RenderableInstance) {
        val mat = outlineMaterial ?: return
        try {
            val count = renderableInstance.materialsCount
            if (count > 0) {
                for (i in 0 until count) {
                    try {
                        renderableInstance.setMaterial(i, mat)
                    } catch (e: IndexOutOfBoundsException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set material at index $i: ${e.message}")
                    }
                }
            } else {
                renderableInstance.setMaterial(mat)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying outline material: ${e.message}", e)
        }
    }

    private fun startOutlineAnimation() {
        if (outlineAnimator != null) return
        outlineAnimator = OutlineAnimator(outlineMaterial).also { it.start() }
    }

    // ---------------------------------------------------------------------------
    // Outline animation (Choreographer-driven animTime)
    // ---------------------------------------------------------------------------

    private class OutlineAnimator(private val material: Material?) {
        private var isAnimating = false
        private var startTimeNanos = 0L

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isAnimating) {
                    updateAnimation()
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        fun start() {
            startTimeNanos = System.nanoTime()
            isAnimating = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        fun stop() {
            isAnimating = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }

        private fun updateAnimation() {
            val mat = material ?: return
            val elapsed = (System.nanoTime() - startTimeNanos) / 1_000_000_000f
            try {
                mat.setFloat("animTime", elapsed)
            } catch (e: Exception) {
                // Material may not be ready yet — ignore
            }
        }
    }
}
