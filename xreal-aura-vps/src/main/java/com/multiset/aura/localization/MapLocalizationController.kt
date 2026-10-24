/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.localization

import android.os.SystemClock
import android.util.Log
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import com.multiset.aura.config.LocalizationConfig
import com.multiset.aura.frame.sdkPose
import com.multiset.aura.pose.TrackingStatus
import com.multiset.aura.sdk.SdkStatus
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.mesh.MapMeshResult
import com.multiset.sdk.mesh.MeshRepository
import com.multiset.sdk.model.AnchorTrust
import com.multiset.sdk.model.CameraFrame
import com.multiset.sdk.model.LocalizationResult
import com.multiset.sdk.model.MultiSetError
import com.multiset.sdk.session.LocalizationSession
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

/**
 * What the map space should show; [com.multiset.aura.scene.MapSpaceRenderer] reconciles the scene
 * to it. [meshes] holds the mesh of every map localized this epoch, by map code: in a map set each
 * member adds its own, and the others stay. [fixes] counts this epoch's successful localizations,
 * each of which restarts the mesh reveal as in the sample.
 */
data class MapScene(
    val epoch: Int = 0,
    val mapPose: Pose? = null,
    val meshes: Map<String, MapMeshResult> = emptyMap(),
    val meshVisible: Boolean = true,
    val fixes: Int = 0,
    /** False hides the gizmo at the map origin. */
    val gizmoVisible: Boolean = true,
)

/**
 * Runs one MultiSet [LocalizationSession] for the map localization screen. It starts only when the
 * screen is in front, the SDK is authenticated, head tracking is live and localizing was asked for.
 * Call it from the main thread; SDK callbacks are moved there.
 */
class MapLocalizationController(
    private val frameSource: FrameSource,
    private val scope: CoroutineScope,
    private val meshCache: MapMeshCache?,
    /** The active MapSet's code, or null against a single map; a set places its member's mesh. */
    private val mapSetCode: () -> String?,
    private val onError: (MultiSetError) -> Unit,
) {

    private val _state = MutableStateFlow(LocalizationState())
    val state: StateFlow<LocalizationState> = _state.asStateFlow()

    private val _scene = MutableStateFlow(MapScene())
    val scene: StateFlow<MapScene> = _scene.asStateFlow()

    private val _lastFalsePositive = MutableStateFlow<String?>(null)

    /** The pose gate's summary of the last fix it rejected. */
    val lastFalsePositive: StateFlow<String?> = _lastFalsePositive.asStateFlow()

    // The SDK localizes one request at a time, so the last frame sent is the one a fix answers.
    @Volatile private var sentCamera: Vector3? = null
    private val sentFrames = object : FrameSource {
        override suspend fun acquire(): CameraFrame? = frameSource.acquire()?.also {
            sentCamera = Vector3(it.cameraPosition.x, it.cameraPosition.y, it.cameraPosition.z)
        }
    }

    private var config = LocalizationConfig()
    private var plan = SessionPlan.of(config)
    private var session: LocalizationSession? = null
    private var running = false
    private var wanted = false
    private var active = false
    private var sdkReady = false
    private var tracking: TrackingStatus = TrackingStatus.NotAvailable
    private var meshRepository: MeshRepository? = null
    private val meshLoads = MapMeshLoads()
    private val meshJobs = mutableMapOf<String, Job>()
    private var lastFixPose: Pose? = null
    private var placedTilt: Float? = null

    fun report(event: LocalizationEvent) = _state.update { reduce(it, event) }

    fun configure(next: LocalizationConfig) {
        val previous = plan
        config = next
        plan = SessionPlan.of(next)
        _scene.update { it.copy(meshVisible = next.showMesh, gizmoVisible = next.showGizmo) }
        report(LocalizationEvent.MeshVisibility(next.showMesh))
        if (session != null && plan.needsNewSession(previous)) {
            val wasRunning = running
            discardSession()
            if (wasRunning) startIfReady()
        }
    }

    fun onSdkStatus(status: SdkStatus) {
        sdkReady = status == SdkStatus.Ready
        if (!sdkReady) {
            // Anything built before MultiSetSDK.release() holds the old SDK's network and auth.
            meshRepository = null
            val wasRunning = running
            discardSession()
            if (wasRunning) report(LocalizationEvent.Stopped)
        }
        startIfReady()
    }

    fun onTrackingStatus(status: TrackingStatus) {
        val signal = trackingSignal(tracking, status)
        tracking = status
        when (signal) {
            TrackingSignal.INTERRUPTED -> session?.notifyTrackingInterrupted()
            TrackingSignal.WORLD_ORIGIN_RESET -> {
                session?.notifyWorldOriginReset()
                reset()
            }
            TrackingSignal.RESUMED -> if (running && plan.relocalization) {
                session?.stop()
                session?.start()
            }
            null -> Unit
        }
        startIfReady()
    }

    /** The map localization screen came to the front. */
    fun activate() {
        active = true
        wanted = wanted || plan.autoLocalize
        startIfReady()
    }

    /** The map localization screen left the front; the placed map stays where it is. */
    fun deactivate() {
        active = false
        if (running) {
            session?.stop()
            running = false
            report(LocalizationEvent.Stopped)
        }
    }

    fun localize() {
        report(LocalizationEvent.Asked(state.value.epoch))
        wanted = true
        if (running) {
            session?.stop()
            running = false
        }
        startIfReady()
    }

    fun reset() {
        dropMeshLoads()
        lastFixPose = null
        placedTilt = null
        discardSession()
        report(LocalizationEvent.Reset)
        _scene.value = MapScene(epoch = state.value.epoch, meshVisible = config.showMesh, gizmoVisible = config.showGizmo)
        wanted = plan.autoLocalize
        startIfReady()
    }

    fun release() {
        dropMeshLoads()
        discardSession()
    }

    private fun dropMeshLoads() {
        meshJobs.values.forEach { it.cancel() }
        meshJobs.clear()
        meshLoads.clear()
    }

    private fun startIfReady() {
        if (!active || !wanted || running || !sdkReady) return
        if (tracking != TrackingStatus.Tracking) {
            report(LocalizationEvent.WaitingForTracking)
            return
        }
        val current = session ?: buildSession().also { session = it }
        current.start()
        running = true
    }

    private fun discardSession() {
        session?.let {
            it.resetSession()
            it.stop()
        }
        session = null
        running = false
    }

    private fun buildSession(): LocalizationSession {
        val epoch = state.value.epoch
        Log.i(TAG, "Session: $plan")
        return MultiSetSDK.localizationSession(sentFrames, plan.mode).apply {
            backgroundLocalization = plan.backgroundLocalization
            bgLocalizationDurationMs = plan.backgroundIntervalMs
            numberOfFrames = plan.numberOfFrames
            frameCaptureIntervalMs = plan.frameCaptureIntervalMs
            confidenceCheck = plan.confidenceCheck
            confidenceThreshold = plan.confidenceThreshold
            firstLocalizationUntilSuccess = plan.firstLocalizationUntilSuccess
            imageQuality = plan.imageQuality
            queryMode = plan.queryMode
            hintMapCodes = plan.hintMapCodes
            // This controller re-localizes itself when tracking resumes and reports no tracking
            // state, so the SDK's recovery would wait forever and hold back the refresh loop.
            relocalization = false
            poseConsistencyCheck = plan.poseConsistencyCheck
            poseConsistencyThreshold = plan.poseConsistencyThreshold
            poseConsistencyYawThreshold = plan.poseConsistencyYawThreshold

            onLocalizationRequested = { onMain { report(LocalizationEvent.Requested(epoch)) } }
            onLocalizationSuccess = { result -> onMain { onLocalized(epoch, result, anchorTrust) } }
            onLocalizationFailure = { error ->
                Log.w(TAG, "Localization failed (${error.kind}): ${error.message}")
                onMain {
                    report(LocalizationEvent.Failed(epoch, error.message, error.isTransient))
                    if (epoch == state.value.epoch) onError(error)
                }
            }
            onLocalizationFalsePositive = { info ->
                Log.w(TAG, "Rejected fix: ${info.summary}")
                onMain {
                    if (epoch == state.value.epoch) _lastFalsePositive.value = info.summary
                    report(LocalizationEvent.FalsePositive(epoch, info.summary))
                }
            }
            // The success that follows carries the same pose, and onLocalized levels and tilt-checks it.
            onLocalizationCorrected = { _, _, trust -> onMain { report(LocalizationEvent.TrustChanged(epoch, trust)) } }
            onAnchorTrustChanged = { trust -> onMain { report(LocalizationEvent.TrustChanged(epoch, trust)) } }
        }
    }

    private fun onLocalized(epoch: Int, result: LocalizationResult, trust: AnchorTrust) {
        Log.i(TAG, "Localized to ${result.mapCode} (confidence ${result.confidence})")
        val fix = sdkPose(result.position, result.rotation)
        val tilt = MapLeveling.tiltDegrees(fix.rotation)
        if (MapLeveling.rejects(tilt, placedTilt)) {
            val summary = "The fix tilted the map %.1f°, so it was ignored.".format(tilt)
            Log.w(TAG, "Rejected fix: $summary")
            report(LocalizationEvent.FalsePositive(epoch, summary))
            return
        }
        report(LocalizationEvent.Succeeded(epoch, result.mapCode, result.confidence, trust))
        if (epoch != state.value.epoch) return
        val camera = sentCamera
        val mapPose = camera?.let { MapLeveling.level(fix, it) } ?: fix
        // Fix-to-fix movement separates a steady offset (calibration) from jumps (timing or pose noise).
        val moved = lastFixPose?.let {
            " moved %.1f cm, %.2f deg since the last fix".format(distanceMetres(it, mapPose) * 100, angleDegrees(it, mapPose))
        }
        val levelled = if (camera != null) ", levelled from %.2f deg about the camera".format(tilt) else ", tilt %.2f deg".format(tilt)
        Log.i(TAG, "Map pose ${mapPose.translation} ${mapPose.rotation}$levelled${moved.orEmpty()}")
        lastFixPose = mapPose
        placedTilt = tilt
        _scene.update { it.copy(mapPose = mapPose, fixes = it.fixes + 1) }
        loadMeshIfNeeded(epoch, result.mapCode)
    }

    /** Each map's mesh downloads on its own: a fix on another map of a set cancels nothing. */
    private fun loadMeshIfNeeded(epoch: Int, mapCode: String) {
        val ticket = meshLoads.start(mapCode) ?: return
        report(LocalizationEvent.MeshLoading(epoch))
        val key = MapMeshCache.Key(mapCode, mapSetCode())
        meshJobs[mapCode] = scope.launch {
            var inScene = false
            try {
                val mesh = loadMesh(key)
                if (epoch != state.value.epoch) return@launch
                if (mesh == null) {
                    report(LocalizationEvent.MeshFailed(epoch, "No mesh is available for map $mapCode."))
                    return@launch
                }
                _scene.update { it.copy(meshes = it.meshes + (mapCode to mesh)) }
                inScene = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Map mesh download failed", e)
                report(LocalizationEvent.MeshFailed(epoch, e.message ?: "Map mesh download failed."))
            } finally {
                // A cancelled job ends only when its download does; its stale ticket is then ignored.
                meshLoads.finish(mapCode, ticket, inScene)
            }
        }
    }

    /** The mesh from the device, or downloaded and then kept; a storage failure only costs the cache. */
    private suspend fun loadMesh(key: MapMeshCache.Key): MapMeshResult? {
        val started = SystemClock.elapsedRealtime()
        val cached = meshCache?.let { cache ->
            withContext(Dispatchers.IO) { runCatching { cache.read(key) }.getOrNull() }
        }
        if (cached != null) {
            Log.i(TAG, "Map mesh for ${key.mapCode} read from the device: ${cached.meshBytes.size / 1024} KiB in ${SystemClock.elapsedRealtime() - started} ms")
            return cached
        }
        val repository = meshRepository ?: MultiSetSDK.meshRepository().also { meshRepository = it }
        val downloaded = repository.loadMapMesh(key.mapCode)
        // loadMapMesh swallows cancellation into null; a superseded load must not report "no mesh".
        currentCoroutineContext().ensureActive()
        val mesh = downloaded ?: return null
        Log.i(TAG, "Map mesh for ${key.mapCode} downloaded: ${mesh.meshBytes.size / 1024} KiB in ${SystemClock.elapsedRealtime() - started} ms")
        meshCache?.let { cache ->
            withContext(Dispatchers.IO) {
                runCatching { cache.write(key, mesh) }.onFailure { Log.w(TAG, "Could not keep the map mesh on the device", it) }
            }
        }
        return mesh
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private companion object {
        const val TAG = "AuraLocalization"
    }
}
