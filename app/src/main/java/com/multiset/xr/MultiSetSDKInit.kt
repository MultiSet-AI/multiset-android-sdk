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
        val config = config(
            clientId = BuildConfig.MULTISET_CLIENT_ID,
            clientSecret = BuildConfig.MULTISET_CLIENT_SECRET,
            mapCode = BuildConfig.MULTISET_MAP_CODE,
            mapSetCode = BuildConfig.MULTISET_MAP_SET_CODE,
            objectCodes = objectCodes(),
            baseUrl = BuildConfig.MULTISET_BASE_URL,
            tokenServerUrl = BuildConfig.MULTISET_TOKEN_SERVER_URL,
        ) ?: return false
        MultiSetSDK.initialize(context.applicationContext, config, callback)
        return true
    }

    /**
     * The SDK config for these `multiset.properties` values; null when every code, or both the
     * credentials and a token server, are missing. A token server is used in place of the credentials.
     */
    fun config(
        clientId: String,
        clientSecret: String,
        mapCode: String,
        mapSetCode: String,
        objectCodes: List<String>,
        baseUrl: String,
        tokenServerUrl: String = "",
    ): MultiSetSDKConfig? {
        val tokenServer = tokenServerUrl.trim()
        if (tokenServer.isEmpty() && (clientId.isEmpty() || clientSecret.isEmpty())) return null
        if (mapCode.isEmpty() && mapSetCode.isEmpty() && objectCodes.isEmpty()) return null

        val builder = if (tokenServer.isNotEmpty()) {
            MultiSetSDKConfig.Builder(TokenServerProvider(tokenServer))
        } else {
            MultiSetSDKConfig.Builder(clientId, clientSecret)
        }
        if (mapCode.isNotEmpty()) builder.mapCode(mapCode) else if (mapSetCode.isNotEmpty()) builder.mapSetCode(mapSetCode)
        if (objectCodes.isNotEmpty()) builder.objectCodes(objectCodes)
        if (baseUrl.isNotBlank()) builder.baseUrl(baseUrl.trim())

        return builder
            .enableMeshVisualization(true)
            .backgroundLocalization(true)
            .build()
    }
}
