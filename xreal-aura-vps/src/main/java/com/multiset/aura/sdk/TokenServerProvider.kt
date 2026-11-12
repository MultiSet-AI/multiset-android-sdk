/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.sdk

import com.google.gson.Gson
import com.multiset.sdk.MultiSetTokenProvider
import com.multiset.sdk.MultiSetTokenRequestReason
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gets each MultiSet token from your own server instead of the client secret: a GET to [url] that
 * answers `{"token": "<JWT>"}`, with `reason=initial`, `expiring` or `rejected` added. A data class,
 * so the configs a rotation rebuilds stay the same account and keep their token.
 */
data class TokenServerProvider(val url: String) : MultiSetTokenProvider {

    override suspend fun token(reason: MultiSetTokenRequestReason): String = suspendCancellableCoroutine { continuation ->
        val target = url.toHttpUrl().newBuilder()
            .addQueryParameter("reason", reason.name.lowercase(Locale.ROOT))
            .build()
        val call = client.newCall(Request.Builder().url(target).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) = response.use {
                val body = it.body?.string()
                val token = body?.let { json -> runCatching { gson.fromJson(json, TokenAnswer::class.java)?.token }.getOrNull() }
                when {
                    !it.isSuccessful -> continuation.resumeWithException(IOException("Token server answered HTTP ${it.code}"))
                    token.isNullOrBlank() -> continuation.resumeWithException(IOException("Token server sent no token"))
                    else -> continuation.resume(token)
                }
            }
        })
    }

    private class TokenAnswer(val token: String?)

    private companion object {
        val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        val gson = Gson()
    }
}
