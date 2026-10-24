/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.config

import com.multiset.aura.sdk.MapTarget

object ConfigCodec {
    const val TARGET = "target"
    const val LOCALIZATION_PREFIX = "loc_"
    const val OBJECT_TRACKING_PREFIX = "obj_"

    fun encode(config: LocalizationConfig): Map<String, Any> = with(config) {
        mapOf(
            "mode" to mode.name,
            "queryMode" to queryMode.name,
            "autoLocalize" to autoLocalize,
            "backgroundLocalization" to backgroundLocalization,
            "backgroundIntervalSeconds" to backgroundIntervalSeconds,
            "relocalization" to relocalization,
            "firstLocalizationUntilSuccess" to firstLocalizationUntilSuccess,
            "numberOfFrames" to numberOfFrames,
            "frameCaptureIntervalMs" to frameCaptureIntervalMs,
            "confidenceCheck" to confidenceCheck,
            "confidenceThreshold" to confidenceThreshold,
            "poseConsistencyCheck" to poseConsistencyCheck,
            "poseConsistencyThreshold" to poseConsistencyThreshold,
            "poseConsistencyYawThreshold" to poseConsistencyYawThreshold,
            "hintMapCodes" to hintMapCodes.joinToString(","),
            "imageQuality" to imageQuality,
            "showAlerts" to showAlerts,
            "showMesh" to showMesh,
            "showGizmo" to showGizmo,
            "cameraPreview" to cameraPreview,
        ).mapKeys { LOCALIZATION_PREFIX + it.key }
    }

    fun encode(config: ObjectTrackingConfig): Map<String, Any> = with(config) {
        mapOf(
            "autoTracking" to autoTracking,
            "backgroundTracking" to backgroundTracking,
            "backgroundIntervalSeconds" to backgroundIntervalSeconds,
            "restartTracking" to restartTracking,
            "firstTrackingUntilSuccess" to firstTrackingUntilSuccess,
            "captureDelayMs" to captureDelayMs,
            "confidenceCheck" to confidenceCheck,
            "confidenceThreshold" to confidenceThreshold,
            "imageQuality" to imageQuality,
            "showAlerts" to showAlerts,
            "showOutline" to showOutline,
        ).mapKeys { OBJECT_TRACKING_PREFIX + it.key }
    }

    fun decodeLocalization(stored: Map<String, *>): LocalizationConfig {
        val read = Reader(stored, LOCALIZATION_PREFIX)
        val d = LocalizationConfig()
        return LocalizationConfig(
            mode = read.enum("mode", d.mode),
            queryMode = read.enum("queryMode", d.queryMode),
            autoLocalize = read.boolean("autoLocalize", d.autoLocalize),
            backgroundLocalization = read.boolean("backgroundLocalization", d.backgroundLocalization),
            backgroundIntervalSeconds = read.int("backgroundIntervalSeconds", d.backgroundIntervalSeconds),
            relocalization = read.boolean("relocalization", d.relocalization),
            firstLocalizationUntilSuccess =
                read.boolean("firstLocalizationUntilSuccess", d.firstLocalizationUntilSuccess),
            numberOfFrames = read.int("numberOfFrames", d.numberOfFrames),
            frameCaptureIntervalMs = read.long("frameCaptureIntervalMs", d.frameCaptureIntervalMs),
            confidenceCheck = read.boolean("confidenceCheck", d.confidenceCheck),
            confidenceThreshold = read.float("confidenceThreshold", d.confidenceThreshold),
            poseConsistencyCheck = read.boolean("poseConsistencyCheck", d.poseConsistencyCheck),
            poseConsistencyThreshold = read.float("poseConsistencyThreshold", d.poseConsistencyThreshold),
            poseConsistencyYawThreshold = read.float("poseConsistencyYawThreshold", d.poseConsistencyYawThreshold),
            hintMapCodes = read.codes("hintMapCodes", d.hintMapCodes),
            imageQuality = read.int("imageQuality", d.imageQuality),
            showAlerts = read.boolean("showAlerts", d.showAlerts),
            showMesh = read.boolean("showMesh", d.showMesh),
            showGizmo = read.boolean("showGizmo", d.showGizmo),
            cameraPreview = read.boolean("cameraPreview", d.cameraPreview),
        ).validated()
    }

    fun decodeObjectTracking(stored: Map<String, *>): ObjectTrackingConfig {
        val read = Reader(stored, OBJECT_TRACKING_PREFIX)
        val d = ObjectTrackingConfig()
        return ObjectTrackingConfig(
            autoTracking = read.boolean("autoTracking", d.autoTracking),
            backgroundTracking = read.boolean("backgroundTracking", d.backgroundTracking),
            backgroundIntervalSeconds = read.int("backgroundIntervalSeconds", d.backgroundIntervalSeconds),
            restartTracking = read.boolean("restartTracking", d.restartTracking),
            firstTrackingUntilSuccess = read.boolean("firstTrackingUntilSuccess", d.firstTrackingUntilSuccess),
            captureDelayMs = read.long("captureDelayMs", d.captureDelayMs),
            confidenceCheck = read.boolean("confidenceCheck", d.confidenceCheck),
            confidenceThreshold = read.float("confidenceThreshold", d.confidenceThreshold),
            imageQuality = read.int("imageQuality", d.imageQuality),
            showAlerts = read.boolean("showAlerts", d.showAlerts),
            showOutline = read.boolean("showOutline", d.showOutline),
        ).validated()
    }

    fun decodeTarget(stored: Map<String, *>): MapTarget? = Reader(stored, "").enumOrNull<MapTarget>(TARGET)

    private class Reader(private val stored: Map<String, *>, private val prefix: String) {
        fun boolean(name: String, default: Boolean): Boolean = stored[prefix + name] as? Boolean ?: default

        fun int(name: String, default: Int): Int = (stored[prefix + name] as? Number)?.toInt() ?: default

        fun long(name: String, default: Long): Long = (stored[prefix + name] as? Number)?.toLong() ?: default

        fun float(name: String, default: Float): Float = (stored[prefix + name] as? Number)?.toFloat() ?: default

        fun codes(name: String, default: List<String>): List<String> =
            (stored[prefix + name] as? String)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

        inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
            (stored[prefix + name] as? String)?.let { value -> enumValues<E>().firstOrNull { it.name == value } }

        inline fun <reified E : Enum<E>> enum(name: String, default: E): E = enumOrNull<E>(name) ?: default
    }
}
