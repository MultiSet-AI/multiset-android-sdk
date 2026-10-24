/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.objecttracking

import android.os.SystemClock
import android.util.Log
import androidx.xr.runtime.math.Pose
import com.multiset.aura.config.ObjectTrackingConfig
import com.multiset.aura.frame.sdkPose
import com.multiset.aura.localization.TrackingSignal
import com.multiset.aura.localization.trackingSignal
import com.multiset.aura.pose.TrackingStatus
import com.multiset.aura.sdk.SdkStatus
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.mesh.MeshRepository
import com.multiset.sdk.model.MultiSetError
import com.multiset.sdk.model.ObjectTrackingResult
import com.multiset.sdk.session.ObjectTrackingSession
import com.multiset.sdk.source.FrameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the object space should show: a placement and, once fetched, an outline mesh per object code. */
data class ObjectScene(
    val epoch: Int = 0,
    val placements: Map<String, Pose> = emptyMap(),
    val meshes: Map<String, ByteArray> = emptyMap(),
    val outlineVisible: Boolean = true,
)

/**
 * Runs one MultiSet [ObjectTrackingSession] for the object tracking screen. It starts only when the
 * screen is in front, the SDK is authenticated, head tracking is live and tracking was asked for.
 * Call it from the main thread; SDK callbacks are moved there.
 */
class ObjectTrackingController(
    private val frameSource: FrameSource,
    private val codes: List<String>,
    private val scope: CoroutineScope,
    private val meshCache: ObjectMeshCache?,
    private val onError: (MultiSetError) -> Unit,
) {

    private val _state = MutableStateFlow(ObjectTrackingState())
    val state: StateFlow<ObjectTrackingState> = _state.asStateFlow()

    private val _scene = MutableStateFlow(ObjectScene())
    val scene: StateFlow<ObjectScene> = _scene.asStateFlow()

    private var config = ObjectTrackingConfig()
    private var plan = TrackingPlan.of(config)
    private var session: ObjectTrackingSession? = null
    private var running = false
    private var wanted = false
    private var active = false
    private var sdkReady = false
    private var tracking: TrackingStatus = TrackingStatus.NotAvailable
    private var meshRepository: MeshRepository? = null
    private val meshJobs = mutableMapOf<String, Job>()

    /** Codes whose mesh failed to bake this epoch and was downloaded again for it. */
    private val redownloaded = mutableSetOf<String>()

    fun report(event: ObjectTrackingEvent) = _state.update { reduce(it, event) }

    fun configure(next: ObjectTrackingConfig) {
        config = next
        plan = TrackingPlan.of(next)
        // The session reads each field when it next uses it, so a live session takes the plan as is.
        session?.applyPlan(plan)
        _scene.update { it.copy(outlineVisible = next.showOutline) }
        report(ObjectTrackingEvent.OutlineVisibility(next.showOutline))
    }

    fun onSdkStatus(status: SdkStatus) {
        sdkReady = status == SdkStatus.Ready
        if (!sdkReady) {
            // Anything built before MultiSetSDK.release() holds the old SDK's network and auth.
            meshRepository = null
            val wasRunning = running
            discardSession()
            if (wasRunning) report(ObjectTrackingEvent.Stopped)
        }
        startIfReady()
    }

    fun onTrackingStatus(status: TrackingStatus) {
        val signal = trackingSignal(tracking, status)
        tracking = status
        when (signal) {
            TrackingSignal.WORLD_ORIGIN_RESET -> reset()
            TrackingSignal.RESUMED -> if (running && plan.restartTracking) {
                session?.stop()
                session?.start()
            }
            // A plain interruption keeps the outlines on their anchors.
            TrackingSignal.INTERRUPTED, null -> Unit
        }
        startIfReady()
    }

    /** The object tracking screen came to the front. */
    fun activate() {
        active = true
        wanted = wanted || plan.autoTracking
        startIfReady()
    }

    /** The object tracking screen left the front; the placed objects stay where they are. */
    fun deactivate() {
        active = false
        if (running) {
            session?.stop()
            running = false
            report(ObjectTrackingEvent.Stopped)
        }
    }

    fun track() {
        report(ObjectTrackingEvent.Asked(state.value.epoch))
        wanted = true
        if (running) {
            session?.stop()
            running = false
        }
        startIfReady()
    }

    /** A new session, not a restart: `ObjectTrackingSession` never re-arms first-tracking-until-success. */
    fun reset() {
        meshJobs.values.forEach(Job::cancel)
        meshJobs.clear()
        redownloaded.clear()
        discardSession()
        report(ObjectTrackingEvent.Reset)
        _scene.value = ObjectScene(epoch = state.value.epoch, outlineVisible = config.showOutline)
        wanted = plan.autoTracking
        startIfReady()
    }

    /**
     * [code]'s mesh, from [epoch], could not be baked into an outline, so it leaves the device. The
     * first failure in an epoch also drops it from the scene, so the next fix downloads it again; a
     * second keeps the failed mesh there, which stops further downloads until a reset.
     */
    fun onBakeFailed(epoch: Int, code: String, message: String) {
        meshCache?.let { cache ->
            scope.launch(Dispatchers.IO) {
                runCatching { cache.remove(code) }.onFailure { Log.w(TAG, "Could not drop the mesh for $code from the device", it) }
            }
        }
        if (epoch == scene.value.epoch && redownloaded.add(code)) _scene.update { it.copy(meshes = it.meshes - code) }
        report(ObjectTrackingEvent.MeshFailed(epoch, code, message))
    }

    fun release() {
        meshJobs.values.forEach(Job::cancel)
        discardSession()
    }

    private fun startIfReady() {
        if (codes.isEmpty() || !active || !wanted || running || !sdkReady) return
        if (tracking != TrackingStatus.Tracking) {
            report(ObjectTrackingEvent.WaitingForTracking)
            return
        }
        val current = session ?: buildSession().also { session = it }
        current.start()
        running = true
    }

    private fun discardSession() {
        session?.stop()
        session = null
        running = false
    }

    private fun buildSession(): ObjectTrackingSession {
        val epoch = state.value.epoch
        Log.i(TAG, "Session for $codes: $plan")
        return MultiSetSDK.objectTrackingSession(frameSource, codes).apply {
            applyPlan(plan)
            onTrackingRequested = { onMain { report(ObjectTrackingEvent.Requested(epoch)) } }
            onTrackingSuccess = { result -> onMain { onTracked(epoch, result) } }
            onTrackingFailure = { error ->
                Log.w(TAG, "Object tracking failed (${error.kind}): ${error.message}")
                onMain {
                    report(ObjectTrackingEvent.Failed(epoch, error.message, error.isTransient))
                    if (epoch == state.value.epoch) onError(error)
                }
            }
        }
    }

    private fun ObjectTrackingSession.applyPlan(plan: TrackingPlan) {
        // This controller restarts tracking itself (TrackingPlan.restartTracking).
        restartTracking = false
        backgroundTracking = plan.backgroundTracking
        bgTrackingDurationMs = plan.backgroundIntervalMs
        firstTrackingUntilSuccess = plan.firstTrackingUntilSuccess
        captureDelayMs = plan.captureDelayMs
        confidenceCheck = plan.confidenceCheck
        confidenceThreshold = plan.confidenceThreshold
        imageQuality = plan.imageQuality
    }

    private fun onTracked(epoch: Int, result: ObjectTrackingResult) {
        Log.i(TAG, "Tracked ${result.objectCode} (confidence ${result.confidence})")
        report(ObjectTrackingEvent.Succeeded(epoch, result.objectCode, result.confidence))
        if (epoch != state.value.epoch) return
        val pose = sdkPose(result.position, result.rotation)
        _scene.update { it.copy(placements = it.placements + (result.objectCode to pose)) }
        loadMeshIfNeeded(epoch, result.objectCode)
    }

    /** Fetches [code]'s mesh unless it is shown or loading; a failed fetch is retried on the next fix. */
    private fun loadMeshIfNeeded(epoch: Int, code: String) {
        if (code in scene.value.meshes || meshJobs[code]?.isActive == true) return
        report(ObjectTrackingEvent.MeshLoading(epoch, code))
        meshJobs[code] = scope.launch {
            try {
                val glb = loadMesh(code)
                if (epoch != state.value.epoch) return@launch
                if (glb == null) {
                    report(ObjectTrackingEvent.MeshFailed(epoch, code, "No mesh is available for object $code."))
                    return@launch
                }
                _scene.update { it.copy(meshes = it.meshes + (code to glb)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Object mesh download failed for $code", e)
                report(ObjectTrackingEvent.MeshFailed(epoch, code, e.message ?: "Object mesh download failed."))
            }
        }
    }

    /** The mesh from the device, or downloaded and then kept; a storage failure only costs the cache. */
    private suspend fun loadMesh(code: String): ByteArray? {
        val started = SystemClock.elapsedRealtime()
        val cached = meshCache?.let { cache ->
            withContext(Dispatchers.IO) { runCatching { cache.read(code) }.getOrNull() }
        }
        if (cached != null) {
            Log.i(TAG, "Mesh for $code read from the device: ${cached.size / 1024} KiB in ${SystemClock.elapsedRealtime() - started} ms")
            return cached
        }
        val repository = meshRepository ?: MultiSetSDK.meshRepository().also { meshRepository = it }
        val glb = withContext(Dispatchers.IO) {
            repository.fetchObjectMeshMetadata(code).fileUrl?.let { repository.downloadMesh(it) }
        }
        // Neither SDK call is cancellable; a fetch superseded by a reset must not keep its mesh.
        currentCoroutineContext().ensureActive()
        if (glb == null) return null
        Log.i(TAG, "Mesh for $code downloaded: ${glb.size / 1024} KiB in ${SystemClock.elapsedRealtime() - started} ms")
        meshCache?.let { cache ->
            withContext(Dispatchers.IO) {
                runCatching { cache.write(code, glb) }.onFailure { Log.w(TAG, "Could not keep the mesh for $code on the device", it) }
            }
        }
        return glb
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private companion object {
        const val TAG = "AuraObjects"
    }
}
