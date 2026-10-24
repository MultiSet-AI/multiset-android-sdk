/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.frame

import com.multiset.sdk.model.DeviceOrientation

/**
 * How to hand a frame to the MultiSet SDK. The SDK takes a landscape image, scales it uniformly to its
 * configured long side and turns it a further 90 degrees clockwise itself for PORTRAIT, so any quarter
 * turn is left to it.
 */
data class SdkFramePlan(val bakedRotationDegrees: Int, val orientation: DeviceOrientation) {
    companion object {
        /** [degrees] is the clockwise rotation that makes the sensor frame upright. */
        fun forUprightRotation(degrees: Int): SdkFramePlan {
            require(degrees in setOf(0, 90, 180, 270)) { "Upright rotation must be a quarter turn, got $degrees" }
            return if (degrees % 180 == 0) {
                SdkFramePlan(degrees, DeviceOrientation.LANDSCAPE)
            } else {
                SdkFramePlan(degrees - 90, DeviceOrientation.PORTRAIT)
            }
        }
    }
}
