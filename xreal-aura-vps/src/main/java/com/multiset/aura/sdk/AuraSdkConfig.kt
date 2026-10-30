/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.sdk

import com.multiset.aura.frame.AuraFramePlan
import com.multiset.sdk.MultiSetSDKConfig

/** The SDK config this sample runs with, for each target `multiset.properties` allows. */
object AuraSdkConfig {
    /** Throws [IllegalArgumentException] as [MultiSetSDKConfig] does; [target] null runs objects only. */
    fun build(credentials: MultiSetCredentials.Ready, target: MapTarget?): MultiSetSDKConfig {
        val builder = MultiSetSDKConfig.Builder(credentials.clientId, credentials.clientSecret)
            .imageLongSide(AuraFramePlan.SDK_LONG_SIDE)
        when (target) {
            MapTarget.MAP -> builder.mapCode(credentials.codeFor(target))
            MapTarget.MAP_SET -> builder.mapSetCode(credentials.codeFor(target))
            null -> Unit
        }
        if (credentials.objectCodes.isNotEmpty()) builder.objectCodes(credentials.objectCodes)
        credentials.baseUrl?.let(builder::baseUrl)
        return builder.build()
    }
}
