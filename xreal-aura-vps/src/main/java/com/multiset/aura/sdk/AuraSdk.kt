/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.sdk

import android.content.Context
import android.util.Log
import com.multiset.aura.BuildConfig
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKCallback
import com.multiset.sdk.model.LocalizationResult
import com.multiset.sdk.model.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Initializes the MultiSet SDK once per process from `multiset.properties` (via BuildConfig). */
object AuraSdk {

    private const val TAG = "AuraSdk"

    private val _status = MutableStateFlow<SdkStatus>(SdkStatus.Authenticating)
    val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _target = MutableStateFlow<MapTarget?>(null)

    /** The map the SDK localizes against; null until [initialize] or with object codes only. */
    val target: StateFlow<MapTarget?> = _target.asStateFlow()

    val credentials: MultiSetCredentials by lazy { MultiSetCredentials.from(::buildConfigValue) }

    private var initialized = false
    private var generation = 0

    /** Safe to call repeatedly; [retry] re-runs authentication after a failure. */
    @Synchronized
    fun initialize(context: Context, preferred: MapTarget?) {
        if (initialized) return
        start(context, preferred)
    }

    @Synchronized
    fun retry(context: Context) = start(context, _target.value)

    /** False when [target] is unavailable or already active; true once the SDK restarts for it. */
    @Synchronized
    fun switchTarget(context: Context, target: MapTarget): Boolean {
        val ready = credentials as? MultiSetCredentials.Ready ?: return false
        if (target !in ready.targets || target == _target.value) return false
        _status.value = SdkStatus.Authenticating
        MultiSetSDK.release()
        start(context, target)
        return true
    }

    private fun start(context: Context, preferred: MapTarget?) {
        val mine = ++generation
        val ready = when (val c = credentials) {
            is MultiSetCredentials.Invalid -> {
                _status.value = SdkStatus.NotConfigured(c.reason)
                return
            }
            is MultiSetCredentials.Ready -> c
        }
        val target = ready.resolve(preferred)
        _target.value = target
        initialized = true
        _status.value = SdkStatus.Authenticating
        try {
            MultiSetSDK.initialize(context.applicationContext, AuraSdkConfig.build(ready, target), callbackFor(mine))
        } catch (e: IllegalArgumentException) {
            initialized = false
            _status.value = SdkStatus.NotConfigured(e.message ?: "multiset.properties holds an invalid value.")
        }
    }

    private fun ifCurrent(generation: Int, block: () -> Unit) = synchronized(this) {
        if (generation == this.generation) block()
    }

    private fun callbackFor(generation: Int) = object : MultiSetSDKCallback {
        // Fires inside initialize, before authentication, so it says nothing about readiness.
        override fun onSDKReady() = Unit

        override fun onAuthenticationSuccess() = ifCurrent(generation) {
            _status.value = SdkStatus.Ready
        }

        override fun onAuthenticationFailure(error: String) = ifCurrent(generation) {
            Log.e(TAG, "MultiSet authentication failed: $error")
            _status.value = SdkStatus.AuthFailed(error)
        }

        // Localization and tracking results arrive through the sessions' own callbacks.
        override fun onLocalizationSuccess(result: LocalizationResult) = Unit
        override fun onLocalizationFailure(error: String) = Unit
        override fun onTrackingStateChanged(state: TrackingState) = Unit
    }

    private fun buildConfigValue(key: String): String = when (key) {
        MultiSetCredentials.CLIENT_ID -> BuildConfig.MULTISET_CLIENT_ID
        MultiSetCredentials.CLIENT_SECRET -> BuildConfig.MULTISET_CLIENT_SECRET
        MultiSetCredentials.MAP_CODE -> BuildConfig.MULTISET_MAP_CODE
        MultiSetCredentials.MAP_SET_CODE -> BuildConfig.MULTISET_MAP_SET_CODE
        MultiSetCredentials.OBJECT_CODES -> BuildConfig.MULTISET_OBJECT_CODES
        MultiSetCredentials.BASE_URL -> BuildConfig.MULTISET_BASE_URL
        else -> ""
    }
}
