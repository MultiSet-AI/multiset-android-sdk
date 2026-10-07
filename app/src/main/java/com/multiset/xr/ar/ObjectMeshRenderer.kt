/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import android.content.Context
import android.util.Log
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.RenderableDefinition
import com.google.ar.sceneform.rendering.Vertex
import com.multiset.sdk.mesh.MeshRepository
import com.multiset.xr.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture

/**
 * Fetches an object's GLB via [MeshRepository] and draws its outline: a copy of the mesh pushed out
 * along its normals and drawn inside out (`outline_only.mat`), behind a depth-only copy
 * (`occluder.mat`) that hides the shell's interior, so only a glowing rim shows at the edges.
 *
 * Port of the iOS SDK's `OutlineMeshRenderer.swift`. The host calls [onFrame] once per AR frame
 * to animate the outline. [MeshRepository] owns the bearer token.
 */
class ObjectMeshRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val meshRepository: MeshRepository,
) {
    companion object {
        private const val TAG = "ObjectMeshRenderer"

        /** iOS `OutlineShader.metal` kOutlineColor / kOutlineAlpha; width 6 mm (`OutlineMeshRenderer.swift`). */
        private val OUTLINE_COLOR = floatArrayOf(0.31950867f, 0.1014151f, 0.5f)
        private const val OUTLINE_ALPHA = 0.5882353f
        private const val OUTLINE_WIDTH = 0.006f
    }

    private val rendererJob = SupervisorJob(scope.coroutineContext[Job])
    private val rendererScope = CoroutineScope(scope.coroutineContext + rendererJob)

    private val meshNodes = mutableMapOf<String, Node>()
    private val parentNodes = mutableMapOf<String, Node>()
    private val fetchedObjectCodes = mutableSetOf<String>()

    private var meshVisible = true
    private var outlineMaterial: CompletableFuture<Material>? = null
    private var occluderMaterial: CompletableFuture<Material>? = null
    private var animatedOutline: Material? = null
    private var outlineClockStart = 0L

    /** Starts loading the outline and occluder materials. Call once before [fetchAndRender]; repeats are no-ops. */
    fun loadMaterials() {
        if (outlineMaterial != null) return
        outlineMaterial = Material.builder()
            .setSource(context, R.raw.outline_only_material)
            .build()
            .thenApply { material ->
                material.apply {
                    setFloat4("outlineColor", OUTLINE_COLOR[0], OUTLINE_COLOR[1], OUTLINE_COLOR[2], OUTLINE_ALPHA)
                    setFloat("outlineWidth", OUTLINE_WIDTH)
                    setFloat("animTime", 0f)
                }
            }
        occluderMaterial = Material.builder()
            .setSource(context, R.raw.occluder_material)
            .build()
    }

    /**
     * Downloads the object's GLB, builds its outline off the main thread, and attaches it under
     * [parentNode]. Idempotent per [objectCode] — later calls for the same code are ignored.
     */
    fun fetchAndRender(objectCode: String, parentNode: Node) {
        if (!fetchedObjectCodes.add(objectCode)) return
        parentNodes[objectCode] = parentNode

        rendererScope.launch {
            try {
                val glb = withContext(Dispatchers.IO) {
                    val fileUrl = meshRepository.fetchObjectMeshMetadata(objectCode).fileUrl
                    if (fileUrl.isNullOrBlank()) null else meshRepository.downloadMesh(fileUrl)
                }
                if (glb == null) {
                    Log.e(TAG, "No mesh URL for object $objectCode")
                    return@launch
                }
                val scene = withContext(Dispatchers.Default) { GlbParser.parse(glb) }
                val outline = outlineMaterial.awaitMaterial("outline")
                val occluder = occluderMaterial.awaitMaterial("occluder")
                if (outline == null && occluder == null) return@launch
                val prepared = withContext(Dispatchers.Default) {
                    scene.roots.map { prepareOutline(it, occluder, outline) }
                }
                showMesh(objectCode, prepared, outline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch/render mesh for $objectCode: ${e.message}", e)
            }
        }
    }

    /** Advances the outline's glow animation. */
    fun onFrame() {
        val outline = animatedOutline ?: return
        if (meshNodes.isEmpty()) return
        outline.setFloat("animTime", (System.nanoTime() - outlineClockStart) / 1e9f)
    }

    /**
     * Detaches every mesh, drops fetches still in flight, and forgets which codes were fetched, so
     * the next fix loads them again. Unlike [release] the renderer stays usable afterwards.
     */
    fun clearMeshes() {
        rendererJob.cancelChildren()
        meshNodes.values.forEach { it.setParent(null) }
        meshNodes.clear()
        parentNodes.clear()
        fetchedObjectCodes.clear()
    }

    /** Whether any object mesh is currently in the scene. */
    fun hasMesh(): Boolean = meshNodes.isNotEmpty()

    /**
     * Shows or hides every object mesh. The choice is remembered, so a mesh that arrives
     * after the host hid them stays hidden rather than popping back in.
     */
    fun setMeshVisible(visible: Boolean) {
        meshVisible = visible
        meshNodes.values.forEach { it.isEnabled = visible }
    }

    /** Cancels pending work and detaches all mesh nodes. Call when the AR session ends. */
    fun release() {
        rendererJob.cancel()
        meshNodes.values.forEach { it.setParent(null) }
        meshNodes.clear()
        parentNodes.clear()
        fetchedObjectCodes.clear()
        animatedOutline = null
    }

    private suspend fun CompletableFuture<Material>?.awaitMaterial(name: String): Material? {
        val future = this ?: return null
        return try {
            // await() cancels the future it waits on; a dependent stage keeps the shared one intact.
            future.thenApply { it }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $name material: ${e.message}", e)
            null
        }
    }

    /** CPU-side outline geometry for one glTF node; [definition] is null for a node without a mesh. */
    private class PreparedNode(
        val transform: GlbTransform,
        val definition: RenderableDefinition?,
        val children: List<PreparedNode>,
    )

    private fun prepareOutline(node: GlbNode, occluder: Material?, outline: Material?): PreparedNode {
        val vertices = ArrayList<Vertex>()
        val indices = ArrayList<Int>()
        for (primitive in node.primitives) {
            if (primitive.vertexCount == 0 || primitive.indices.isEmpty()) continue
            val normals = primitive.normals ?: OutlineNormals.smoothNormals(primitive.positions, primitive.indices)
            val positions = primitive.positions
            val base = vertices.size
            for (v in 0 until primitive.vertexCount) {
                vertices += Vertex.builder()
                    .setPosition(Vector3(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]))
                    .setNormal(Vector3(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2]))
                    .build()
            }
            appendTriangles(primitive.indices, primitive.vertexCount, base, indices)
        }
        val submeshes = listOfNotNull(occluder, outline).map { material ->
            RenderableDefinition.Submesh.builder().setTriangleIndices(indices).setMaterial(material).build()
        }
        val definition = if (indices.isEmpty()) null else {
            RenderableDefinition.builder().setVertices(vertices).setSubmeshes(submeshes).build()
        }
        return PreparedNode(node.transform, definition, node.children.map { prepareOutline(it, occluder, outline) })
    }

    /** Copies whole triangles only; an out-of-range index would read past the vertex buffer on the GPU. */
    private fun appendTriangles(source: IntArray, vertexCount: Int, base: Int, out: MutableList<Int>) {
        var i = 0
        while (i + 2 < source.size) {
            val a = source[i]
            val b = source[i + 1]
            val c = source[i + 2]
            i += 3
            if (a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
                out += base + a
                out += base + b
                out += base + c
            }
        }
    }

    private fun showMesh(objectCode: String, prepared: List<PreparedNode>, outline: Material?) {
        val parent = parentNodes[objectCode] ?: return
        if (meshNodes.containsKey(objectCode)) return
        val root = Node().apply { name = objectCode }
        root.setParent(parent)
        prepared.forEach { buildNode(it).setParent(root) }
        root.isEnabled = meshVisible
        meshNodes[objectCode] = root
        animatedOutline = outline
        if (outlineClockStart == 0L) outlineClockStart = System.nanoTime()
        Log.d(TAG, "Outline rendered for object: $objectCode")
    }

    private fun buildNode(prepared: PreparedNode): Node {
        val node = Node()
        node.localPosition = prepared.transform.translation.toSceneform()
        node.localRotation = prepared.transform.rotation.toSceneform()
        node.localScale = prepared.transform.scale.toSceneform()
        prepared.definition?.let { definition ->
            ModelRenderable.builder()
                .setSource(definition)
                .build()
                .thenAccept { node.renderable = it }
                .exceptionally { throwable ->
                    Log.e(TAG, "Failed to build outline renderable: ${throwable.message}", throwable)
                    null
                }
        }
        prepared.children.forEach { buildNode(it).setParent(node) }
        return node
    }
}
