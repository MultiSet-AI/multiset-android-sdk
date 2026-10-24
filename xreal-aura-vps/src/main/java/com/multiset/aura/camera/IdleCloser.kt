/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs [onIdle] once [timeoutMs] after the most recent [touch], unless touched again or cancelled. */
class IdleCloser(private val scope: CoroutineScope, private val timeoutMs: Long, private val onIdle: suspend () -> Unit) {

    private var pending: Job? = null

    @Synchronized
    fun touch() {
        pending?.cancel()
        pending = scope.launch {
            delay(timeoutMs)
            onIdle()
        }
    }

    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
