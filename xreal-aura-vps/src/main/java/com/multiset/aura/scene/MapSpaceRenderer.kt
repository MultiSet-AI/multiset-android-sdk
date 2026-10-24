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
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.GltfModelNode
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import com.multiset.aura.frame.sdkPose
import com.multiset.aura.localization.LocalizationEvent
import com.multiset.aura.localization.MapScene
import com.multiset.aura.localization.MeshStatus
import com.multiset.aura.scene.glb.GizmoGlb
import com.multiset.aura.scene.glb.GridPalette
import com.multiset.aura.scene.glb.MapMeshGlb
import com.multiset.aura.scene.glb.MeshReveal
import com.multiset.aura.scene.glb.MeshRevealSweep
import com.multiset.aura.scene.glb.MeshVisibility
import com.multiset.sdk.mesh.MapMeshResult
import com.multiset.sdk.model.Vec3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shows a [MapScene] in SceneCore: an anchored map root carrying the origin gizmo and the mesh of
 * every localized map in the MultiSet sample's grid, each revealed outward from the wearer in the
 * sample's loop ([MeshReveal]). In a map set each member's mesh stays once shown, as on iOS.
 * [wearer] gives the current head pose in perception space; [scope] runs reveal restarts. Call
 * everything from the main thread.
 */
class MapSpaceRenderer(
    context: Context,
    private val session: Session,
    private val palette: GridPalette,
    private val scope: CoroutineScope,
    private val wearer: suspend () -> Pose?,
    private val report: (LocalizationEvent) -> Unit,
) {

    private val mapRoot = Entity.create(session, "MapSpace").apply { setEnabled(false) }
    private var epoch = -1

    // Every epoch change clears the placement, so a pending anchor always belongs to [epoch].
    private val placement = AnchorPlacement(
        context,
        session,
        mapRoot,
        onBlocked = ::block,
        onFailed = ::failPlacement,
    )
    private var gizmo: GltfModelEntity? = null
    private val meshes = LinkedHashMap<String, LoadedMesh>()
    private var meshVisible = true
    private var seenFixes = 0

    // Every cut, a new mesh's first included, takes its turn: each is a whole-map GLB build, and two
    // at once is what can run a second map out of memory. Waiting in line, no mesh's reveal is starved.
    private val cutLock = Mutex()
    private var lastViewCheck = 0L
    private var nodeRotation: NodeRotation? = null
    private var blocked = false

    // The state may still hold a block this renderer, or the one it replaced, reported.
    private var maybeBlocked = true

    // Maps whose mesh failed to show this epoch: baking them again would only fail again.
    private val failedMeshes = mutableSetOf<String>()

    private class LoadedMesh(val result: MapMeshResult, val local: Pose, var cut: MeshCut) {
        var sweep: MeshRevealSweep? = null
        var restart: Job? = null
    }

    /**
     * The mesh cut into shells around [centre] (mesh frame); node `look * shells + i` draws shell i.
     * With no [shellMids], the mesh shows whole and its nodes are never touched.
     */
    private class MeshCut(
        val model: GltfModel,
        val entity: GltfModelEntity,
        val centre: Vec3,
        private val shellMids: FloatArray,
        val maxRadius: Float,
        private val rotation: NodeRotation,
    ) {
        private val looks = IntArray(shellMids.size) { MeshReveal.HIDDEN }

        fun show(radius: Float) {
            for (shell in shellMids.indices) {
                val look = MeshReveal.look(shellMids[shell], radius)
                val previous = looks[shell]
                if (look == previous) continue
                // Hide first: a frame with neither beats one where both z-fight.
                if (previous != MeshReveal.HIDDEN) rotation.shrink(node(shell, previous), HIDDEN_SCALE)
                if (look != MeshReveal.HIDDEN) rotation.grow(node(shell, look), FULL_SCALE)
                looks[shell] = look
            }
        }

        fun hideAll() = show(-1f)

        private fun node(shell: Int, look: Int) = entity.nodes[look * shellMids.size + shell].setters()

        fun close() {
            entity.disposeNow()
            model.close()
        }
    }

    fun supports3dContent(): Boolean = SpatialCapability.SPATIAL_3D_CONTENT in session.scene.spatialCapabilities

    suspend fun sync(scene: MapScene) {
        if (scene.epoch != epoch) {
            clear()
            epoch = scene.epoch
        }
        val switchedOn = scene.meshVisible && !meshVisible
        meshVisible = scene.meshVisible
        val pose = scene.mapPose ?: return
        if (!supports3dContent()) {
            block(MeshStatus.NeedsFullSpace)
            return
        }
        blocked = false
        placement.place(pose)
        // A map nobody can anchor would only report MeshShown over the block.
        if (blocked) return
        if (maybeBlocked) {
            maybeBlocked = false
            report(LocalizationEvent.MeshUnblocked(epoch, meshShown = meshes.isNotEmpty()))
        }
        if (gizmo == null && scene.gizmoVisible) {
            gizmo = GltfModelEntity.create(session, loadGlb(GizmoGlb.build(), "map-gizmo"), Pose(), mapRoot)
        }
        gizmo?.setEnabled(scene.gizmoVisible)
        scene.meshes.values.forEach { if (it.mapId !in meshes && it.mapId !in failedMeshes) showMesh(it, scene.epoch) }
        meshes.values.forEach { shown ->
            // Switched on, it sweeps in from the wearer instead of popping in at the hidden sweep's radius.
            if (switchedOn) {
                shown.cut.hideAll()
                shown.sweep = null
            }
            shown.cut.entity.setEnabled(meshVisible)
        }
        // Like the sample, every successful localization sweeps again from where the wearer is:
        // tick() starts each once any cut in flight is done.
        if (scene.fixes != seenFixes) {
            seenFixes = scene.fixes
            meshes.values.forEach { if (it.sweep != null) it.sweep = null }
        }
    }

    /** Completes anchor hand-offs and advances every reveal; call once per frame. */
    fun tick() {
        placement.tick()
        if (meshes.isEmpty()) return
        val now = System.nanoTime()
        val checkView = now - lastViewCheck > VIEW_CHECK_NANOS
        if (checkView) lastViewCheck = now
        meshes.values.forEach { tick(it, now, checkView) }
    }

    private fun tick(shown: LoadedMesh, now: Long, checkView: Boolean) {
        val sweep = shown.sweep
        if (sweep == null) {
            // The first sweep waits until the mesh is actually visible: anchored and switched on.
            if (placement.isAnchored && shown.cut.entity.isEnabled()) restartReveal(shown)
            return
        }
        shown.cut.show(sweep.radius(now))
        if (sweep.isOver(now)) {
            restartReveal(shown)
        } else if (checkView) {
            followWearer(shown)
        }
    }

    fun release() {
        // A destroyed session has already disposed every entity.
        if (mapRoot.disposed) return
        clear()
        gizmo?.parent = null
        gizmo = null
        mapRoot.parent = null
    }

    private fun block(status: MeshStatus) {
        blocked = true
        maybeBlocked = true
        report(LocalizationEvent.MeshBlocked(epoch, status))
    }

    private fun failPlacement(reason: String) {
        Log.w(TAG, "Could not anchor the map: $reason")
        report(LocalizationEvent.MeshFailed(epoch, "Could not anchor the map: $reason."))
    }

    /** Adds [result]'s mesh beside those already shown. */
    private suspend fun showMesh(result: MapMeshResult, epoch: Int) {
        try {
            val local = sdkPose(result.localPosition, result.localRotation)
            val wearer = wearerInMesh(local)
            // The sample falls back to the mesh's own origin when it has no camera position.
            val cut = loadCut(result, local, wearer ?: Vec3(0f, 0f, 0f), wearer)
            attach(cut)
            meshes[result.mapId] = LoadedMesh(result, local, cut)
            Log.i(TAG, "Map mesh ${result.mapId} added at ${local.translation} ${local.rotation}; ${meshes.size} in the scene")
            report(LocalizationEvent.MeshShown(epoch))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the map mesh", e)
            failedMeshes += result.mapId
            report(LocalizationEvent.MeshFailed(epoch, "Could not load the map mesh: ${e.message}"))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "The map mesh is too large to show", e)
            failedMeshes += result.mapId
            report(LocalizationEvent.MeshFailed(epoch, "The map mesh is too large to show on this device."))
        }
    }

    // Each sweep starts at the wearer, or at the last centre while tracking is lost, as in the sample.
    private fun restartReveal(shown: LoadedMesh) {
        if (shown.restart?.isActive == true) return
        shown.restart = scope.launch {
            recutAroundWearer(shown, MeshReveal.RECUT_DISTANCE_M)
            if (isShown(shown)) shown.sweep = MeshRevealSweep(shown.cut.maxRadius, System.nanoTime())
        }
    }

    /** Between sweeps, keeps the visible surfaces in step with a walking wearer, without a new sweep. */
    private fun followWearer(shown: LoadedMesh) {
        if (shown.restart?.isActive == true) return
        shown.restart = scope.launch { recutAroundWearer(shown, MeshVisibility.RECUT_DISTANCE_M) }
    }

    private suspend fun recutAroundWearer(shown: LoadedMesh, minDistance: Float) {
        try {
            val centre = wearerInMesh(shown.local) ?: return
            if (meshVisible && MeshReveal.needsRecut(centre, shown.cut.centre, minDistance)) recut(shown, centre)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not cut the mesh around the wearer; keeping the last cut", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to cut the mesh around the wearer; keeping the last cut", e)
        }
    }

    /** Shells and the visible surfaces are fixed at load, so a new place needs the mesh cut again. */
    private suspend fun recut(shown: LoadedMesh, centre: Vec3) {
        val cut = loadCut(shown.result, shown.local, centre, centre)
        if (!isShown(shown)) {
            cut.close()
            return
        }
        attach(cut)
        shown.cut.close()
        shown.cut = cut
        // Its shells are measured from the new centre, so the running sweep must reach the new cut's radius.
        shown.sweep = shown.sweep?.resized(cut.maxRadius)
    }

    private suspend fun loadCut(result: MapMeshResult, local: Pose, centre: Vec3, viewpoint: Vec3?): MeshCut =
        cutLock.withLock { cut(result, local, centre, viewpoint) }

    private suspend fun cut(result: MapMeshResult, local: Pose, centre: Vec3, viewpoint: Vec3?): MeshCut {
        val started = System.nanoTime()
        val reveal = nodeRotation != NodeRotation.UNKNOWN
        val prepared = withContext(Dispatchers.Default) {
            MapMeshGlb.prepare(result.meshBytes, palette, centre.takeIf { reveal }, viewpoint)
        }
        val cutAt = System.nanoTime()
        // The runtime caches models by key, and every cut is a different GLB.
        val model = loadGlb(prepared.glb, "map-grid-${result.mapId}-${nextCut++}")
        // Created detached, so no frame draws the shells and glow twins before they are hidden.
        // No entity alpha: while a fade set it, Aura drew the mesh opaque whatever the texture's alpha.
        val entity = GltfModelEntity.create(session, model, local)
        val shells = prepared.revealDistances.size
        val glowLevels = if (reveal) MeshReveal.GLOW_LEVELS else 0
        if (entity.nodes.size != shells * (1 + glowLevels)) {
            entity.disposeNow()
            model.close()
            error("the model has ${entity.nodes.size} nodes for $shells shells")
        }
        val rotation = nodeRotation ?: detectNodeRotation(entity).also { nodeRotation = it }
        if (reveal && rotation == NodeRotation.UNKNOWN) {
            entity.disposeNow()
            model.close()
            return cut(result, local, centre, viewpoint)
        }
        if (reveal) entity.nodes.forEach { rotation.shrink(it.setters(), HIDDEN_SCALE) }
        val mids = if (reveal) FloatArray(shells) { prepared.revealDistances[it] + prepared.revealBand / 2 } else FloatArray(0)
        val maxRadius = MeshReveal.maxRadius(centre, prepared.chunks)
        Log.i(
            TAG,
            "Mesh cut: $shells shells of ${prepared.revealBand} m to ${"%.1f".format(maxRadius)} m, " +
                (if (viewpoint != null) "surfaces seen from the wearer, " else "every surface, ") +
                "${prepared.glb.size / 1024} KiB, cut in ${(cutAt - started) / 1_000_000} ms, " +
                "loaded in ${(System.nanoTime() - cutAt) / 1_000_000} ms"
        )
        return MeshCut(model, entity, centre, mids, maxRadius, rotation)
    }

    /** Sets the identity on a fresh node, before anything shows it, and reads back what was stored. */
    private fun detectNodeRotation(entity: GltfModelEntity): NodeRotation {
        val node = entity.nodes.first()
        node.localPose = Pose()
        val rotation = NodeRotation.detect(node.localPose.rotation)
        Log.i(TAG, "glTF node rotations: $rotation" + if (rotation == NodeRotation.UNKNOWN) "; the mesh shows without the reveal" else "")
        return rotation
    }

    private fun attach(cut: MeshCut) {
        cut.entity.setEnabled(meshVisible)
        cut.entity.parent = mapRoot
    }

    /** The wearer's head position in the mesh's own frame, where the shells are cut; null if unknown. */
    private suspend fun wearerInMesh(local: Pose): Vec3? {
        val root = placement.targetPose ?: return null
        val head = wearer() ?: return null
        val p = root.compose(local).inverse.transformPoint(head.translation)
        return Vec3(p.x, p.y, p.z)
    }

    // rc01's public loaders only take asset paths or URLs; meshes downloaded at runtime need this one.
    @SuppressLint("RestrictedApi")
    private suspend fun loadGlb(bytes: ByteArray, key: String): GltfModel = GltfModel.create(session, bytes, key)

    private fun isShown(shown: LoadedMesh) = meshes[shown.result.mapId] === shown

    private fun removeMeshes() {
        meshes.values.forEach {
            it.restart?.cancel()
            it.cut.close()
        }
        meshes.clear()
    }

    private fun clear() {
        removeMeshes()
        failedMeshes.clear()
        seenFixes = 0
        placement.clear()
    }

    private companion object {
        const val TAG = "MapLocalization"

        // GltfModelNode has no visibility switch in rc01, so hidden nodes are shrunk to nothing.
        val HIDDEN_SCALE = Vector3(1e-4f, 1e-4f, 1e-4f)
        val FULL_SCALE = Vector3(1f, 1f, 1f)

        const val VIEW_CHECK_NANOS = 1_000_000_000L

        var nextCut = 0

        fun GltfModelNode.setters() = object : NodeSetters {
            override fun setRotation(rotation: Quaternion) {
                localPose = Pose(Vector3(0f, 0f, 0f), rotation)
            }

            override fun setScale(scale: Vector3) {
                localScale = scale
            }
        }
    }
}
