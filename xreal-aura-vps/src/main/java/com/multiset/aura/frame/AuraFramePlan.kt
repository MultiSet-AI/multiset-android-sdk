/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.frame

import com.multiset.sdk.MultiSetSDKConfig

/**
 * How a camera still becomes the frame handed to the MultiSet SDK: [resample] scales the whole still,
 * uncropped, to [SDK_LONG_SIDE] on its long side and bakes any half turn; [sdk] names the orientation
 * the SDK is told. The SDK is configured with the same long side, so the frame passes through at
 * scale 1.0.
 */
data class AuraFramePlan(val resample: FrameResamplePlan, val sdk: SdkFramePlan) {
    companion object {
        /** The largest the server takes: Aura's 1920x1080 still goes out at 1280x720. */
        const val SDK_LONG_SIDE = MultiSetSDKConfig.MAX_IMAGE_LONG_SIDE

        fun create(streamWidth: Int, streamHeight: Int, sensorOrientation: Int, displayRotation: Int): AuraFramePlan {
            val sdk = SdkFramePlan.forUprightRotation(
                FrameResamplePlan.uprightRotation(sensorOrientation, displayRotation)
            )
            return AuraFramePlan(
                FrameResamplePlan.create(streamWidth, streamHeight, SDK_LONG_SIDE, sdk.bakedRotationDegrees),
                sdk,
            )
        }
    }
}
