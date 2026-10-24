/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import com.multiset.aura.localization.MeshStatus
import com.multiset.aura.objecttracking.ObjectScene
import com.multiset.aura.objecttracking.ObjectTrackingEvent
import com.multiset.aura.scene.glb.ObjectOutlineGlb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows an [ObjectScene] in SceneCore: per object code an anchored root carrying that object's
 * outline. [report] tells the view model what each outline is doing; [onBakeFailed] hands back a
 * mesh that could not be turned into an outline. [scope] runs the outline loads. Call everything
 * from the main thread.
 */
class ObjectSpaceRenderer(
    private val context: Context,
    private val session: Session,
    private val scope: CoroutineScope,
    private val report: (ObjectTrackingEvent) -> Unit,
    private val onBakeFailed: (epoch: Int, code: String, message: String) -> Unit,
) {

    private var epoch = -1
    private var outlineVisible = true
    private val objects = mutableMapOf<String, PlacedObject>()
    private var blocked = false

    // The state may still hold a block this renderer, or the one it replaced, reported.
    private var maybeBlocked = true

    // Every epoch change drops every object, so a pending anchor always belongs to [epoch].
    private inner class PlacedObject(val code: String) {
        val root = Entity.create(session, "Object-$code").apply { setEnabled(false) }
        val placement = AnchorPlacement(
            context,
            session,
            root,
            onBlocked = ::block,
            onFailed = { reason -> failPlacement(code, reason) },
        )
        var outline: Outline? = null

        /** The mesh the outline was, or is being, made from; a new one replaces the outline. */
        var source: ByteArray? = null
        var loading: Job? = null
    }

    private class Outline(val model: GltfModel, val entity: GltfModelEntity) {
        fun close() {
            entity.disposeNow()
            model.close()
        }
    }

    fun supports3dContent(): Boolean = SpatialCapability.SPATIAL_3D_CONTENT in session.scene.spatialCapabilities

    suspend fun sync(scene: ObjectScene) {
        if (scene.epoch != epoch) {
            clear()
            epoch = scene.epoch
        }
        outlineVisible = scene.outlineVisible
        objects.values.forEach { it.outline?.entity?.setEnabled(outlineVisible) }
        if (scene.placements.isEmpty()) return
        if (!supports3dContent()) {
            block(MeshStatus.NeedsFullSpace)
            return
        }
        blocked = false
        scene.placements.forEach { (code, pose) -> objects.getOrPut(code) { PlacedObject(code) }.placement.place(pose) }
        // An outline nobody can anchor would only report MeshShown over the block.
        if (blocked) return
        if (maybeBlocked) {
            maybeBlocked = false
            report(ObjectTrackingEvent.MeshUnblocked(epoch, objects.filterValues { it.outline != null }.keys))
        }
        scene.meshes.forEach { (code, mesh) ->
            val placed = objects[code] ?: return@forEach
            if (placed.source !== mesh) load(placed, mesh)
        }
    }

    /** Completes anchor hand-offs; call once per frame. */
    fun tick() {
        objects.values.forEach { it.placement.tick() }
    }

    fun release() = clear()

    private fun block(status: MeshStatus) {
        blocked = true
        maybeBlocked = true
        report(ObjectTrackingEvent.MeshBlocked(epoch, status))
    }

    private fun failPlacement(code: String, reason: String) {
        Log.w(TAG, "Could not anchor $code: $reason")
        report(ObjectTrackingEvent.MeshFailed(epoch, code, "Could not anchor $code: $reason."))
    }

    private fun load(placed: PlacedObject, mesh: ByteArray) {
        placed.loading?.cancel()
        placed.source = mesh
        val epoch = epoch
        val code = placed.code
        placed.loading = scope.launch {
            val glb = try {
                withContext(Dispatchers.Default) { ObjectOutlineGlb.build(mesh) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not bake the outline of $code", e)
                // A superseded load may still fail its bake; only a current one reports it.
                if (isActive) onBakeFailed(epoch, code, "Could not read the mesh of $code: ${e.message}")
                return@launch
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "The mesh of $code is too large to outline", e)
                report(ObjectTrackingEvent.MeshFailed(epoch, code, "The mesh of $code is too large to show on this device."))
                return@launch
            }
            try {
                // Not cancellable, so a model loaded for a removed object is still closed.
                val model = withContext(NonCancellable) { loadGlb(glb, "object-$code-${nextModel++}") }
                if (!isActive) {
                    model.close()
                    return@launch
                }
                // Created detached, then parented; no entity alpha, which Aura draws opaque.
                val entity = try {
                    GltfModelEntity.create(session, model, Pose())
                } catch (e: Exception) {
                    model.close()
                    throw e
                }
                entity.setEnabled(outlineVisible)
                entity.parent = placed.root
                placed.outline?.close()
                placed.outline = Outline(model, entity)
                report(ObjectTrackingEvent.MeshShown(epoch, code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not show the outline of $code", e)
                report(ObjectTrackingEvent.MeshFailed(epoch, code, "Could not show the outline of $code: ${e.message}"))
            }
        }
    }

    // rc01's public loaders take only asset paths or URLs; downloaded meshes need this one.
    @SuppressLint("RestrictedApi")
    private suspend fun loadGlb(bytes: ByteArray, key: String): GltfModel = GltfModel.create(session, bytes, key)

    private fun clear() {
        objects.values.forEach(::remove)
        objects.clear()
    }

    private fun remove(placed: PlacedObject) {
        placed.loading?.cancel()
        // A destroyed session has already disposed every entity.
        if (placed.root.disposed) return
        placed.outline?.close()
        placed.placement.clear()
        placed.root.parent = null
    }

    private companion object {
        const val TAG = "AuraObjects"

        // The runtime caches models by key, and a re-downloaded mesh is a different GLB.
        var nextModel = 0
    }
}
