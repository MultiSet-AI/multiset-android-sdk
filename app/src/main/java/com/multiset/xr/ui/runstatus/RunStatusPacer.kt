/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.runstatus

/**
 * Gives each step of a run long enough on screen to be read.
 *
 * On a fast network a single-frame run captures, uploads and is answered inside half a second,
 * which drew as a flicker. A step is held for its minimum before the next shows; the steps queue
 * meanwhile, and an answer makes a queued loader redundant.
 */
class RunStatusPacer {
    var shown: RunStatusState = RunStatusState.Hidden
        private set

    private var shownAtMs = 0L
    private val queue = ArrayDeque<RunStatusState>()
    private var uploadEndedWhileQueued = false

    val latest: RunStatusState
        get() = queue.lastOrNull() ?: shown

    val nextChangeMs: Long?
        get() = queue.firstOrNull()?.let { dwellEnd(it) }

    fun receive(state: RunStatusState, nowMs: Long) {
        advance(nowMs)
        if (!state.isInProgress) queue.removeAll { it == RunStatusState.Working }
        if (state != RunStatusState.Hidden && queue.lastOrNull() == RunStatusState.Hidden) {
            queue.removeLast()
        }
        if (sameStep(latest, state)) {
            if (queue.isEmpty()) shown = state else queue[queue.size - 1] = state
        } else {
            queue.addLast(state)
        }
        advance(nowMs)
    }

    fun advance(nowMs: Long) {
        while (queue.isNotEmpty() && nowMs >= dwellEnd(queue.first())) {
            shown = queue.removeFirst()
            shownAtMs = nowMs
            uploadEndedWhileQueued = shown == RunStatusState.Uploading && queue.isNotEmpty()
        }
    }

    private fun dwellEnd(next: RunStatusState): Long = when (val current = shown) {
        RunStatusState.Hidden -> shownAtMs
        RunStatusState.Working -> shownAtMs + WORKING_DWELL_MS
        is RunStatusState.Capturing -> shownAtMs + CAPTURE_DWELL_MS
        RunStatusState.Uploading ->
            if (uploadEndedWhileQueued) shownAtMs + QUEUED_UPLOAD_DWELL_MS else shownAtMs
        RunStatusState.Succeeded, RunStatusState.Failed -> {
            val hold = current.holdDurationMs
            if (next == RunStatusState.Hidden && hold != null) {
                shownAtMs + hold
            } else {
                shownAtMs + RESULT_DWELL_MS
            }
        }
    }

    /** A frame taken is a flash on the step already showing, not a step of its own. */
    private fun sameStep(a: RunStatusState, b: RunStatusState): Boolean =
        (a is RunStatusState.Capturing && b is RunStatusState.Capturing) || a == b

    companion object {
        const val CAPTURE_DWELL_MS = 600L

        /** The SDK times the upload itself; only one that ended while queued is held, to be seen. */
        const val QUEUED_UPLOAD_DWELL_MS = 800L
        const val WORKING_DWELL_MS = 500L

        /** A result gives way this soon to the next run; only fading waits out its whole hold. */
        const val RESULT_DWELL_MS = 1000L
    }
}
