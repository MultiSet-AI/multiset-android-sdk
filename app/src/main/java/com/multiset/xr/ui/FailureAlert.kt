/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import android.app.Activity
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.multiset.sdk.model.MultiSetError
import com.multiset.xr.R

/**
 * Presents actionable failures (rejected request, bad credentials, unreachable API) as a
 * modal alert carrying the API's own explanation. Transient scan failures are filtered out
 * by [FailureAlertGate] and stay on the toast/status path.
 */
class FailureAlert(@StringRes private val operationTitleRes: Int) {
    private val gate = FailureAlertGate()
    private var dialog: AlertDialog? = null

    /** Call when the user re-triggers a scan, so a recurring failure is reported again. */
    fun reset() = gate.reset()

    fun show(activity: Activity, error: MultiSetError) {
        if (!gate.shouldShow(error)) return
        if (activity.isFinishing || activity.isDestroyed) return

        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(activity)
            .setIcon(icon(activity, error))
            .setTitle(title(activity, error))
            .setMessage(body(activity, error))
            .setPositiveButton(R.string.error_dismiss) { shown, _ -> shown.dismiss() }
            .setOnDismissListener { dialog = null }
            .show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun icon(activity: Activity, error: MultiSetError) = when (error.kind) {
        MultiSetError.Kind.AUTH -> tinted(activity, R.drawable.ic_lock, R.color.brand_ink)
        MultiSetError.Kind.NETWORK -> tinted(activity, R.drawable.ic_wifi_off, R.color.text_mid)
        else -> tinted(activity, R.drawable.ic_error_circle, R.color.danger_ink)
    }

    private fun tinted(activity: Activity, @DrawableRes id: Int, @ColorRes tint: Int) =
        ContextCompat.getDrawable(activity, id)!!.mutate().also {
            DrawableCompat.setTint(it, ContextCompat.getColor(activity, tint))
        }

    private fun title(activity: Activity, error: MultiSetError): String = when (error.kind) {
        MultiSetError.Kind.AUTH -> activity.getString(R.string.error_title_auth)
        MultiSetError.Kind.NETWORK -> activity.getString(R.string.error_title_network)
        else -> {
            val operation = activity.getString(operationTitleRes)
            error.statusCode?.let { activity.getString(R.string.error_title_with_code, operation, it) }
                ?: operation
        }
    }

    private fun body(activity: Activity, error: MultiSetError): String = when (error.kind) {
        MultiSetError.Kind.AUTH ->
            activity.getString(R.string.error_body_auth, error.statusCode ?: 401)
        MultiSetError.Kind.NETWORK -> activity.getString(R.string.error_body_network)
        else -> error.message
    }
}
