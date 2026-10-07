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
import com.google.android.filament.MaterialInstance
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.EngineInstance
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.RenderableInstance
import com.multiset.sdk.mesh.MapMeshResult
import com.multiset.sdk.model.Vec3
import com.multiset.xr.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.android.filament.Material as FilamentMaterial

/**
 * Renders a VPS map mesh onto a Sceneform [Node] with the radial-reveal material
 * (`radial_reveal.mat`): see-through purple with a 1 m yellow grid, uncovered by a circle that grows
 * outward from the camera, holds for [MeshRevealTiming.DELAY_BETWEEN_LOOPS] seconds, then sweeps
 * again from wherever the camera is.
 *
 * Port of the iOS SDK's map-mesh reveal (`MeshRenderer.swift`, `MeshRevealAnimator.swift`). The host
 * calls [onFrame] once per AR frame and [release] when the scene goes away.
 */
class MeshRenderer(private val context: Context) {

    companion object {
        private const val TAG = "MeshRenderer"

        /** Persistent GLB cache — not evicted by the OS unlike cacheDir. */
        private const val CACHE_DIR = "mesh_cache"
    }

    private var revealMaterial: FilamentMaterial? = null
    private var mesh: RevealedMesh? = null
    private var currentMapId: String? = null
    private var meshVisible = true
    private var loadGeneration = 0

    /**
     * Render the map mesh described by [meshResult] as a child of [parentNode].
     *
     * If the same map is already shown, this restarts the reveal from [cameraWorldPosition]
     * without re-loading the model.
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
        val shown = mesh
        if (shown != null && currentMapId == meshResult.mapId) {
            shown.restart(System.nanoTime(), cameraWorldPosition)
            onComplete(true)
            return
        }

        val cacheFile = resolveCacheFile(meshResult.mapId, meshResult.meshBytes)
        if (cacheFile == null) {
            Log.e(TAG, "Failed to resolve cache file for mapId=${meshResult.mapId}")
            onComplete(false)
            return
        }

        val generation = ++loadGeneration
        ModelRenderable.builder()
            .setSource(context, Uri.fromFile(cacheFile))
            .setIsFilamentGltf(true)
            .setRegistryId(meshResult.mapId)
            .build()
            .thenAccept { renderable ->
                if (generation != loadGeneration) {
                    onComplete(false)
                    return@thenAccept
                }
                removeMesh()
                showMesh(renderable, meshResult, parentNode, cameraWorldPosition)
                onComplete(true)
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Error loading GLB mesh: ${throwable.message}", throwable)
                onComplete(false)
                null
            }
    }

    /** Advances the reveal; [cameraWorldPosition] is null while ARCore is not tracking. */
    fun onFrame(cameraWorldPosition: Vector3?) {
        mesh?.tick(System.nanoTime(), cameraWorldPosition)
    }

    /**
     * Shows or hides the map mesh. The choice is remembered, so a mesh loaded after the host
     * hid them stays hidden rather than popping back in. The reveal keeps running either way,
     * so unhiding lands mid-sweep rather than restarting.
     */
    fun setMeshVisible(visible: Boolean) {
        meshVisible = visible
        mesh?.node?.isEnabled = visible
    }

    /** Takes the mesh down and drops any load still in flight. The renderer stays usable. */
    fun removeMesh() {
        loadGeneration++
        mesh?.destroy()
        mesh = null
        currentMapId = null
    }

    fun hasMesh(): Boolean = mesh != null

    /** [removeMesh] plus the compiled reveal material; call when the AR scene goes away. */
    fun release() {
        removeMesh()
        revealMaterial?.let { material ->
            if (!EngineInstance.isEngineDestroyed()) EngineInstance.getEngine().destroyMaterial(material)
        }
        revealMaterial = null
    }

    private fun showMesh(
        renderable: ModelRenderable,
        meshResult: MapMeshResult,
        parentNode: Node,
        cameraWorldPosition: Vector3?,
    ) {
        val node = Node()
        node.setParent(parentNode)
        val instance = node.setRenderable(renderable)
        node.localPosition = Vector3(meshResult.localPosition[0], meshResult.localPosition[1], meshResult.localPosition[2])
        node.localRotation = Quaternion(
            meshResult.localRotation[0],
            meshResult.localRotation[1],
            meshResult.localRotation[2],
            meshResult.localRotation[3]
        )
        node.localScale = Vector3.one()
        node.isEnabled = meshVisible

        val reveal = revealInstance()
        val replaced = if (reveal != null) replaceMaterials(instance, reveal) else emptyList()
        val centre = cameraWorldPosition ?: node.worldPosition
        mesh = RevealedMesh(node, instance, reveal, replaced, centre)
        currentMapId = meshResult.mapId
    }

    private fun revealInstance(): MaterialInstance? {
        val material = revealMaterial ?: loadRevealMaterial()?.also { revealMaterial = it } ?: return null
        return material.createInstance().apply { setParameter("reveal", 0f, 0f, 0f, 0f) }
    }

    private fun loadRevealMaterial(): FilamentMaterial? = runCatching {
        val bytes = context.resources.openRawResource(R.raw.radial_reveal_material).use { it.readBytes() }
        val payload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
        FilamentMaterial.Builder().payload(payload, bytes.size).build(EngineInstance.getEngine().filamentEngine)
    }.onFailure { Log.e(TAG, "Failed to load reveal material", it) }.getOrNull()

    /**
     * Binds [material] to every primitive of every entity in the GLB and returns what it replaced.
     * Sceneform's own `setMaterial(index)` keys glTF bindings by entity and so skips any primitive past
     * the first.
     */
    private fun replaceMaterials(instance: RenderableInstance, material: MaterialInstance): List<Binding> {
        val asset = instance.filamentAsset ?: return emptyList()
        val manager = EngineInstance.getEngine().renderableManager
        val replaced = mutableListOf<Binding>()
        for (entity in asset.entities) {
            val renderable = manager.getInstance(entity)
            if (renderable == 0) continue
            for (primitive in 0 until manager.getPrimitiveCount(renderable)) {
                replaced += Binding(entity, primitive, manager.getMaterialInstanceAt(renderable, primitive))
                manager.setMaterialInstanceAt(renderable, primitive, material)
            }
        }
        return replaced
    }

    /** A primitive's material before the reveal replaced it. */
    private class Binding(val entity: Int, val primitive: Int, val original: MaterialInstance)

    private inner class RevealedMesh(
        val node: Node,
        private val instance: RenderableInstance,
        private val material: MaterialInstance?,
        private val replaced: List<Binding>,
        initialCentre: Vector3,
    ) {
        private var centre = initialCentre
        private var startNanos = System.nanoTime()
        private var holding = false
        private var maxRadius = computeMaxRadius()
        private var duration = MeshRevealTiming.duration(maxRadius)
        private var lastRadius = -1f

        fun tick(now: Long, camera: Vector3?) {
            val m = material ?: return
            val elapsed = (now - startNanos) / 1e9f
            if (holding) {
                if (elapsed >= MeshRevealTiming.DELAY_BETWEEN_LOOPS) restart(now, camera)
                return
            }
            val progress = (elapsed / duration).coerceIn(0f, 1f)
            val radius = progress * maxRadius
            if (radius != lastRadius) {
                m.setParameter("reveal", centre.x, centre.y, centre.z, radius)
                lastRadius = radius
            }
            if (progress >= 1f) {
                holding = true
                startNanos = now
            }
        }

        /** Sweeps again from [camera], or from the last centre while tracking is lost. */
        fun restart(now: Long, camera: Vector3?) {
            if (camera != null) centre = camera
            maxRadius = computeMaxRadius()
            duration = MeshRevealTiming.duration(maxRadius)
            holding = false
            startNanos = now
        }

        fun destroy() {
            if (material != null && !EngineInstance.isEngineDestroyed()) {
                val engine = EngineInstance.getEngine()
                val manager = engine.renderableManager
                // Rebind the originals so no renderable still points at the instance destroyed below.
                for (binding in replaced) {
                    val renderable = manager.getInstance(binding.entity)
                    if (renderable != 0) manager.setMaterialInstanceAt(renderable, binding.primitive, binding.original)
                }
                engine.destroyMaterialInstance(material)
            }
            node.setParent(null)
            node.renderable = null
        }

        private fun computeMaxRadius(): Float {
            val box = instance.filamentAsset?.boundingBox
                ?: return MeshRevealTiming.maxRadius(centre.toVec3(), null, null)
            val c = box.center
            val h = box.halfExtent
            val world = node.worldModelMatrix
            var min = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var max = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
                val p = world.transformPoint(Vector3(c[0] + sx * h[0], c[1] + sy * h[1], c[2] + sz * h[2]))
                min = Vec3(minOf(min.x, p.x), minOf(min.y, p.y), minOf(min.z, p.z))
                max = Vec3(maxOf(max.x, p.x), maxOf(max.y, p.y), maxOf(max.z, p.z))
            }
            return MeshRevealTiming.maxRadius(centre.toVec3(), min, max)
        }
    }

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
}
