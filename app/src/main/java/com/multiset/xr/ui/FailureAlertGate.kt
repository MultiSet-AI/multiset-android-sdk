/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import com.multiset.sdk.model.MultiSetError

/**
 * Decides whether a failure deserves a modal alert.
 *
 * Sessions keep retrying a rejected request on the background interval, so the same
 * server error arrives repeatedly — each distinct failure is surfaced only once until
 * [reset] (the user re-triggering a scan).
 */
class FailureAlertGate {
    private val shown = mutableSetOf<String>()

    fun shouldShow(error: MultiSetError): Boolean {
        if (error.isTransient) return false
        return shown.add(signature(error))
    }

    fun reset() = shown.clear()

    private fun signature(error: MultiSetError) = "${error.kind}|${error.statusCode}|${error.message}"
}
