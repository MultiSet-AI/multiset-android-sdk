/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.pose

/** Device tracking from Jetpack XR's head tracker. */
sealed class TrackingStatus(val message: String) {
    data object NotAvailable : TrackingStatus("Tracking isn't available on this device.")
    data object PermissionRequired : TrackingStatus("Head tracking permission has not been granted.")
    data object Starting : TrackingStatus("Starting tracking…")
    data object Tracking : TrackingStatus("Tracking.")
    data class NotTracking(val stopped: Boolean) :
        TrackingStatus(if (stopped) "Tracking stopped." else "Tracking is paused.")
    data class Failed(val reason: String) : TrackingStatus("Tracking failed: $reason")
}
