/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

/**
 * The preview target currently attached. Only the owner of that target can detach it, so a late
 * detach from a replaced owner leaves the newer target in place.
 */
class PreviewSlot<T : Any> {

    var current: T? = null
        private set

    fun attach(target: T) {
        current = target
    }

    /** Returns true if [target] was the attached one and is now removed. */
    fun detach(target: T): Boolean {
        if (current !== target) return false
        current = null
        return true
    }

    fun clear() {
        current = null
    }
}
