/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.runstatus

import com.multiset.sdk.model.LocalizationStage

sealed class RunStatusState {
    data object Hidden : RunStatusState()

    /** The run is open with nothing being taken or sent: waiting on the server or a retry. */
    data object Working : RunStatusState()

    /** Taking frames; [shots] counts those in, so each one can be shown as it is taken. */
    data class Capturing(val shots: Int) : RunStatusState()

    data object Uploading : RunStatusState()
    data object Succeeded : RunStatusState()
    data object Failed : RunStatusState()

    val isInProgress: Boolean
        get() = this is Working || this is Capturing || this is Uploading

    /** How long a result stays before it fades; null for the states that do not fade. */
    val holdDurationMs: Long?
        get() = when (this) {
            Succeeded -> SUCCESS_HOLD_MS
            Failed -> FAILURE_HOLD_MS
            else -> null
        }

    companion object {
        private const val SUCCESS_HOLD_MS = 2000L
        private const val FAILURE_HOLD_MS = 3000L

        fun from(stage: LocalizationStage): RunStatusState = when (stage) {
            LocalizationStage.Idle -> Hidden
            is LocalizationStage.Capturing -> Capturing(stage.captured)
            LocalizationStage.Uploading -> Uploading
            LocalizationStage.AwaitingResponse -> Working
        }
    }
}
