/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping

import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlin.math.abs

/** Picks the camera image a map scan uses. */
object CameraConfigs {
    private const val TARGET_LONG_SIDE = 1920
    private const val MIN_LONG_SIDE = 960
    private const val FOUR_BY_THREE = 4f / 3f
    private const val RATIO_TOLERANCE = 0.01f

    /** The back camera's 4:3 config nearest 1920 wide (at least 960), else its widest; null when none. */
    fun select(session: Session): CameraConfig? {
        val filter = CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK)
        val configs = session.getSupportedCameraConfigs(filter)
        val index = chooseIndex(configs.map { it.imageSize.width to it.imageSize.height })
        return configs.getOrNull(index)
    }

    /** Index of the preferred size in [sizes], or -1 when empty. */
    fun chooseIndex(sizes: List<Pair<Int, Int>>): Int {
        val indices = sizes.indices
        val preferred = indices
            .filter { isFourByThree(sizes[it]) && longSide(sizes[it]) >= MIN_LONG_SIDE }
            .minByOrNull { abs(longSide(sizes[it]) - TARGET_LONG_SIDE) }
        return preferred ?: indices.maxByOrNull { longSide(sizes[it]) } ?: -1
    }

    private fun longSide(size: Pair<Int, Int>) = maxOf(size.first, size.second)

    private fun isFourByThree(size: Pair<Int, Int>): Boolean {
        val short = minOf(size.first, size.second)
        return short > 0 && abs(longSide(size).toFloat() / short - FOUR_BY_THREE) < RATIO_TOLERANCE
    }
}

/** The ARCore session settings a map scan runs with. */
object MappingSessionConfig {
    /** Configures [config] and [session] for scanning; returns whether depth is on. */
    fun apply(session: Session, config: Config): Boolean {
        CameraConfigs.select(session)?.let { session.cameraConfig = it }
        val depth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.depthMode = if (depth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        return depth
    }
}
