/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Window-inset helpers. targetSdk 36 means the system draws content edge to edge and ignores
 * `android:statusBarColor`, so every screen has to inset its own chrome or it ends up under the
 * status bar and the gesture handle.
 *
 * Both helpers capture the view's designed padding/margin once, so re-dispatched insets add to it
 * instead of accumulating, and they return the insets unchanged so sibling views still receive them.
 */
private val BAR_TYPES =
    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

/** Insets a full-width container by padding it; content clips at the bars rather than under them. */
fun View.padForSystemBars(top: Boolean = false, bottom: Boolean = false) {
    val basePaddingTop = paddingTop
    val basePaddingBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(BAR_TYPES)
        view.updatePadding(
            top = if (top) basePaddingTop + bars.top else basePaddingTop,
            bottom = if (bottom) basePaddingBottom + bars.bottom else basePaddingBottom,
        )
        insets
    }
}

/**
 * Insets a floating view by shifting its margins — used for AR overlay chrome, where the camera
 * surface underneath must stay full-bleed and only the controls move.
 */
fun View.marginForSystemBars(top: Boolean = false, bottom: Boolean = false) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val baseTopMargin = params.topMargin
    val baseBottomMargin = params.bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(BAR_TYPES)
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (top) topMargin = baseTopMargin + bars.top
            if (bottom) bottomMargin = baseBottomMargin + bars.bottom
        }
        insets
    }
}
