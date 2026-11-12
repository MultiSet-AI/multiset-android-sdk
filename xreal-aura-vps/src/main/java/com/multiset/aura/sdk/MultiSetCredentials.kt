/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.sdk

enum class MapTarget { MAP, MAP_SET }

sealed interface MultiSetCredentials {
    data class Ready(
        val clientId: String,
        val clientSecret: String,
        val mapCode: String?,
        val mapSetCode: String?,
        val objectCodes: List<String>,
        val baseUrl: String?,
        // Set, the SDK gets its tokens there, and clientId and clientSecret are empty.
        val tokenServerUrl: String? = null,
    ) : MultiSetCredentials {
        val targets: List<MapTarget> = listOfNotNull(
            MapTarget.MAP.takeIf { mapCode != null },
            MapTarget.MAP_SET.takeIf { mapSetCode != null },
        )

        /** [preferred] when configured, else the first configured target; null with no map code. */
        fun resolve(preferred: MapTarget?): MapTarget? = preferred?.takeIf { it in targets } ?: targets.firstOrNull()

        fun codeFor(target: MapTarget): String = when (target) {
            MapTarget.MAP -> mapCode!!
            MapTarget.MAP_SET -> mapSetCode!!
        }
    }

    data class Invalid(val reason: String) : MultiSetCredentials

    companion object {
        const val CLIENT_ID = "MULTISET_CLIENT_ID"
        const val CLIENT_SECRET = "MULTISET_CLIENT_SECRET"
        const val MAP_CODE = "MULTISET_MAP_CODE"
        const val MAP_SET_CODE = "MULTISET_MAP_SET_CODE"
        const val OBJECT_CODES = "MULTISET_OBJECT_CODES"
        const val BASE_URL = "MULTISET_BASE_URL"
        const val TOKEN_SERVER_URL = "MULTISET_TOKEN_SERVER_URL"

        /** The most object codes `MultiSetSDKConfig` accepts. */
        const val MAX_OBJECT_CODES = 10

        /** [read] returns a `multiset.properties` value by key, or "" when it is absent. */
        fun from(read: (String) -> String): MultiSetCredentials {
            fun value(key: String) = read(key).trim().ifEmpty { null }
            fun missing(key: String) = Invalid("Set $key, or $TOKEN_SERVER_URL, in multiset.properties and rebuild.")

            val tokenServerUrl = value(TOKEN_SERVER_URL)
            val clientId = if (tokenServerUrl != null) "" else value(CLIENT_ID) ?: return missing(CLIENT_ID)
            val clientSecret = if (tokenServerUrl != null) "" else value(CLIENT_SECRET) ?: return missing(CLIENT_SECRET)
            val mapCode = value(MAP_CODE)
            val mapSetCode = value(MAP_SET_CODE)
            val objectCodes = read(OBJECT_CODES).split(',').map(String::trim).filter(String::isNotEmpty)
            if (objectCodes.size > MAX_OBJECT_CODES) {
                return Invalid(
                    "$OBJECT_CODES lists ${objectCodes.size} codes; the SDK accepts at most $MAX_OBJECT_CODES.",
                )
            }
            if (mapCode == null && mapSetCode == null && objectCodes.isEmpty()) {
                return Invalid("Set $MAP_CODE, $MAP_SET_CODE or $OBJECT_CODES in multiset.properties and rebuild.")
            }
            return Ready(clientId, clientSecret, mapCode, mapSetCode, objectCodes, value(BASE_URL), tokenServerUrl)
        }
    }
}
