/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.scene

import android.annotation.SuppressLint
import androidx.xr.scenecore.Entity

/**
 * Disposes this entity and its children now, before the models they were made from are closed. A
 * detached entity is only reclaimed at garbage collection, and its teardown clears its nodes'
 * material overrides, reading the model's materials: a model closed first aborts the renderer with
 * "unknown material instance id". rc01 deprecates and restricts dispose() in favour of detaching.
 */
@SuppressLint("RestrictedApi")
@Suppress("DEPRECATION")
fun Entity.disposeNow() {
    if (!isDisposed) dispose()
}

/** Whether the entity is gone, as every entity is once its session is destroyed. */
val Entity.disposed: Boolean
    @SuppressLint("RestrictedApi") get() = isDisposed
