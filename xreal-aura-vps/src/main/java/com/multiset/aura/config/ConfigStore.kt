/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.multiset.aura.sdk.MapTarget

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadLocalization(): LocalizationConfig = ConfigCodec.decodeLocalization(prefs.all)

    fun loadObjectTracking(): ObjectTrackingConfig = ConfigCodec.decodeObjectTracking(prefs.all)

    fun loadTarget(): MapTarget? = ConfigCodec.decodeTarget(prefs.all)

    fun save(config: LocalizationConfig) = prefs.edit { putAll(ConfigCodec.encode(config)) }

    fun save(config: ObjectTrackingConfig) = prefs.edit { putAll(ConfigCodec.encode(config)) }

    fun saveTarget(target: MapTarget) = prefs.edit { putString(ConfigCodec.TARGET, target.name) }

    fun resetToDefaults() = prefs.edit {
        val settingPrefixes = listOf(ConfigCodec.LOCALIZATION_PREFIX, ConfigCodec.OBJECT_TRACKING_PREFIX)
        prefs.all.keys.filter { key -> settingPrefixes.any(key::startsWith) }.forEach(::remove)
    }

    private fun SharedPreferences.Editor.putAll(entries: Map<String, Any>) {
        entries.forEach { (key, value) ->
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                else -> throw IllegalArgumentException("Unsupported setting type ${value::class} for $key")
            }
        }
    }

    private companion object {
        const val PREFS = "multiset_aura"
    }
}
