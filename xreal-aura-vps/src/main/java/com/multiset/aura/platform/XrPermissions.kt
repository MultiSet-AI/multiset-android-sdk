/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.platform

import android.content.Context
import android.content.pm.PackageManager

object XrPermissions {
    const val HEAD_TRACKING = "android.permission.HEAD_TRACKING"
    const val SCENE_UNDERSTANDING_COARSE = "android.permission.SCENE_UNDERSTANDING_COARSE"
}

/** Headsets require these permissions; a device that doesn't define one can't grant it either. */
fun Context.isXrPermissionMissing(permission: String): Boolean {
    val defined = runCatching { packageManager.getPermissionInfo(permission, 0) }.isSuccess
    return defined && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
}
