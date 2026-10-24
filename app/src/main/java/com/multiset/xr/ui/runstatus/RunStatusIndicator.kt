/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.runstatus

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * What the status card shows, paced so each step is seen. Fed from the SDK's stage callbacks and
 * its result callbacks; a result fades on its own. Main thread only.
 */
class RunStatusIndicator(
    private val clockMs: () -> Long = SystemClock::uptimeMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onStateChanged: (RunStatusState) -> Unit,
) {
    private val pacer = RunStatusPacer()
    private var isShowingResult = false
    private var published: RunStatusState = RunStatusState.Hidden
    private val advanceRunnable = Runnable { advance() }

    fun receive(next: RunStatusState) {
        val now = clockMs()
        if (next != RunStatusState.Hidden) isShowingResult = next.holdDurationMs != null
        pacer.receive(next, now)
        // The SDK ends a run before it reports the result, so nothing else would fade the result.
        if (next.holdDurationMs != null) pacer.receive(RunStatusState.Hidden, now)
        publish()
    }

    /** A run the user is waiting on stays open between its silent retries, with no stage to show. */
    fun keepOpen() {
        if (pacer.latest != RunStatusState.Hidden || isShowingResult) return
        receive(RunStatusState.Working)
    }

    fun release() {
        handler.removeCallbacks(advanceRunnable)
    }

    private fun advance() {
        pacer.advance(clockMs())
        publish()
    }

    private fun publish() {
        val next = pacer.shown
        if (next != published) {
            published = next
            onStateChanged(next)
        }
        handler.removeCallbacks(advanceRunnable)
        val due = pacer.nextChangeMs ?: return
        handler.postDelayed(advanceRunnable, (due - clockMs()).coerceAtLeast(0))
    }
}
