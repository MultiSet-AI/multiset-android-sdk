/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ar

import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.multiset.sdk.model.Quat
import com.multiset.sdk.model.Vec3

fun Vector3.toVec3(): Vec3 = Vec3(x, y, z)
fun Vec3.toSceneform(): Vector3 = Vector3(x, y, z)
fun Quaternion.toQuat(): Quat = Quat(x, y, z, w)
fun Quat.toSceneform(): Quaternion = Quaternion(x, y, z, w)
