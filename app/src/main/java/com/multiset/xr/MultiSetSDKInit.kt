/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr

import android.content.Context
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.MultiSetSDKCallback
import com.multiset.sdk.MultiSetSDKConfig

object MultiSetSDKInit {
    fun objectCodes(): List<String> =
        BuildConfig.MULTISET_OBJECT_CODES.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Returns true if credentials/codes are present and init was attempted. */
    fun initialize(context: Context, callback: MultiSetSDKCallback): Boolean {
        val clientId = BuildConfig.MULTISET_CLIENT_ID
        val clientSecret = BuildConfig.MULTISET_CLIENT_SECRET
        val mapCode = BuildConfig.MULTISET_MAP_CODE
        val mapSetCode = BuildConfig.MULTISET_MAP_SET_CODE
        val objectCodes = objectCodes()
        if (clientId.isEmpty() || clientSecret.isEmpty()) return false
        if (mapCode.isEmpty() && mapSetCode.isEmpty() && objectCodes.isEmpty()) return false

        val builder = MultiSetSDKConfig.Builder(clientId, clientSecret)
        if (mapCode.isNotEmpty()) builder.mapCode(mapCode) else if (mapSetCode.isNotEmpty()) builder.mapSetCode(mapSetCode)
        if (objectCodes.isNotEmpty()) builder.objectCodes(objectCodes)

        // Optional: point the SDK at a non-production API host (multiset.properties).
        // Left unset, the SDK uses MultiSetSDKConfig.DEFAULT_BASE_URL.
        val baseUrl = BuildConfig.MULTISET_BASE_URL.trim()
        if (baseUrl.isNotEmpty()) builder.baseUrl(baseUrl)

        val config = builder.enableMeshVisualization(true).backgroundLocalization(true).build()
        MultiSetSDK.initialize(context.applicationContext, config, callback)
        return true
    }
}
