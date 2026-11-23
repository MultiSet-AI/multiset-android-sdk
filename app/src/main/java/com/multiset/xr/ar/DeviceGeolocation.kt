/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ArSceneView
import com.multiset.sdk.MultiSetSDK
import com.multiset.sdk.model.AnchorPose
import com.multiset.sdk.model.Geolocation
import com.multiset.sdk.model.HeadingMode
import com.multiset.sdk.model.Quat
import com.multiset.sdk.model.Vec3

/**
 * Where the device is on Earth right now, and which way it faces; null without a tracking camera or a
 * [MultiSetSDK.geoAlignment].
 *
 * Uses the map anchor as the scene shows it, so the answer matches the screen while smoothing is on.
 *
 * @param headingMode [HeadingMode.CAMERA_FORWARD] for the direction the camera looks, or
 *   [HeadingMode.DEVICE_TOP] for the device's top edge, as for a phone held flat over a 2D map.
 */
fun MultiSetSDK.deviceGeolocation(
    sceneView: ArSceneView,
    mapAnchor: MapAnchor?,
    headingMode: HeadingMode = HeadingMode.CAMERA_FORWARD,
): Geolocation? {
    var alignment = geoAlignment ?: return null
    val camera = trackingCamera(sceneView) ?: return null
    mapAnchor?.renderedAnchor?.let { alignment = alignment.replacingAnchor(it) }
    return alignment.geolocationOfCamera(camera.position, camera.rotation, headingMode)
}

/**
 * The device's pose in the map right now, the "you are here" of a floor plan; null without a tracking
 * camera or a [MultiSetSDK.mapAlignment].
 *
 * The rotation follows the camera pose a query sends: in portrait it carries the same 90° correction
 * about the camera's Z axis.
 */
fun MultiSetSDK.deviceMapPose(sceneView: ArSceneView, mapAnchor: MapAnchor?): AnchorPose? {
    var alignment = mapAlignment ?: return null
    val camera = trackingCamera(sceneView) ?: return null
    mapAnchor?.renderedAnchor?.let { alignment = alignment.replacingAnchor(it) }
    return alignment.mapPoseOfCamera(camera.position, camera.rotation, sceneView.context.deviceOrientation())
}

// ARCore's camera pose is only valid while tracking.
private fun trackingCamera(sceneView: ArSceneView): AnchorPose? {
    val camera = sceneView.arFrame?.camera?.takeIf { it.trackingState == TrackingState.TRACKING } ?: return null
    val pose = camera.pose
    return AnchorPose(Vec3(pose.tx(), pose.ty(), pose.tz()), Quat(pose.qx(), pose.qy(), pose.qz(), pose.qw()))
}
