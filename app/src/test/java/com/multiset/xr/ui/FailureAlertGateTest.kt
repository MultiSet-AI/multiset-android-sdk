/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import com.multiset.sdk.model.MultiSetError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureAlertGateTest {

    private val mixedGenerations = MultiSetError(
        kind = MultiSetError.Kind.SERVER,
        message = "All objects in a multi-object query must belong to the same generation.",
        statusCode = 400,
    )

    @Test
    fun transientFailures_neverRaiseTheDialog() {
        val gate = FailureAlertGate()
        assertFalse(gate.shouldShow(MultiSetError.transient("Pose not found")))
        assertFalse(gate.shouldShow(MultiSetError.transient("Low confidence: 0.21")))
    }

    @Test
    fun serverFailure_raisesTheDialogOnce() {
        val gate = FailureAlertGate()
        assertTrue(gate.shouldShow(mixedGenerations))
        assertFalse(gate.shouldShow(mixedGenerations))
    }

    @Test
    fun differentServerFailure_raisesTheDialogAgain() {
        val gate = FailureAlertGate()
        gate.shouldShow(mixedGenerations)
        assertTrue(gate.shouldShow(mixedGenerations.copy(message = "MapSet not found", statusCode = 404)))
    }

    @Test
    fun sameMessageWithDifferentStatusCode_isADistinctFailure() {
        val gate = FailureAlertGate()
        gate.shouldShow(mixedGenerations)
        assertTrue(gate.shouldShow(mixedGenerations.copy(statusCode = 500)))
    }

    @Test
    fun reset_allowsTheSameFailureToBeShownAgain() {
        val gate = FailureAlertGate()
        gate.shouldShow(mixedGenerations)
        gate.reset()
        assertTrue(gate.shouldShow(mixedGenerations))
    }

    @Test
    fun networkFailure_raisesTheDialog() {
        val gate = FailureAlertGate()
        assertTrue(gate.shouldShow(MultiSetError(MultiSetError.Kind.NETWORK, "unreachable")))
    }

    @Test
    fun authFailure_raisesTheDialog() {
        val gate = FailureAlertGate()
        assertTrue(gate.shouldShow(MultiSetError(MultiSetError.Kind.AUTH, "Unauthorized", 401)))
    }
}
