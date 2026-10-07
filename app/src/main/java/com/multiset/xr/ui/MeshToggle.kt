/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.multiset.xr.R

/**
 * Drives the bottom-left mesh chip on both AR screens: it reports taps and renders its own
 * on/off styling, and the caller owns what showing and hiding a mesh actually means.
 *
 * Offer it only once a mesh is in the scene — a control that hides nothing reads as broken.
 */
class MeshToggle(
    private val button: MaterialButton,
    private val onChange: (Boolean) -> Unit,
) {

    var isOn: Boolean = true
        private set

    init {
        button.setOnClickListener { set(!isOn) }
        render()
    }

    /** Shows or hides the chip itself, following whether the scene has a mesh to hide. */
    fun setAvailable(available: Boolean) {
        val target = if (available) View.VISIBLE else View.GONE
        if (button.visibility != target) button.visibility = target
    }

    private fun set(on: Boolean) {
        if (on == isOn) return
        isOn = on
        render()
        onChange(on)
    }

    private fun render() {
        val context = button.context
        val fill = ContextCompat.getColor(context, if (isOn) R.color.ar_brand else R.color.ar_scrim)
        val content = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (isOn) R.color.ar_on else R.color.ar_on_mid)
        )
        button.backgroundTintList = ColorStateList.valueOf(fill)
        button.setTextColor(content)
        button.iconTint = content
        button.contentDescription =
            context.getString(if (isOn) R.string.mesh_shown else R.string.mesh_hidden)
    }
}
