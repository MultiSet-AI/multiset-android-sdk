/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import android.view.animation.AccelerateDecelerateInterpolator
import com.multiset.xr.R
import com.multiset.xr.ui.runstatus.animationsDisabled

/** The progress strip for a mesh a fix has asked for. The SDK reports no size, so it sweeps. */
class MeshLoadingBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val label: TextView
    private val track: FrameLayout
    private val sweep: View
    private var animator: ValueAnimator? = null
    private val maxWidthPx = (MAX_WIDTH_DP * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_mesh_loading_bar)
        val horizontal = (PADDING_HORIZONTAL_DP * resources.displayMetrics.density).toInt()
        val vertical = (PADDING_VERTICAL_DP * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        LayoutInflater.from(context).inflate(R.layout.view_mesh_loading_bar, this, true)
        label = findViewById(R.id.meshLoadingLabel)
        track = findViewById(R.id.meshLoadingTrack)
        sweep = findViewById(R.id.meshLoadingSweep)
        track.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ -> onTrackWidth(right - left) }
        ViewCompat.setStateDescription(this, context.getString(R.string.mesh_loading_in_progress))
    }

    fun setLabel(text: CharSequence) {
        label.text = text
        contentDescription = text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val capped = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST)
            MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(minOf(size, maxWidthPx), MeasureSpec.EXACTLY)
            else -> MeasureSpec.makeMeasureSpec(minOf(size, maxWidthPx), MeasureSpec.AT_MOST)
        }
        super.onMeasure(capped, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    private fun onTrackWidth(width: Int) {
        val segment = (width * SEGMENT_FRACTION).toInt()
        val params = sweep.layoutParams as ViewGroup.LayoutParams
        if (params.width != segment) {
            params.width = segment
            sweep.layoutParams = params
        }
        if (animator == null) sweep.translationX = 0f
    }

    private fun updateAnimation() {
        if (isAttachedToWindow && isShown && !context.animationsDisabled()) startAnimation() else stopAnimation()
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            interpolator = AccelerateDecelerateInterpolator()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                sweep.translationX = track.width * TRAVEL_FRACTION * (it.animatedValue as Float)
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        sweep.translationX = 0f
    }

    private companion object {
        const val MAX_WIDTH_DP = 240
        const val PADDING_HORIZONTAL_DP = 12
        const val PADDING_VERTICAL_DP = 8
        const val SWEEP_MS = 1100L
        const val SEGMENT_FRACTION = 0.35f
        const val TRAVEL_FRACTION = 0.65f
    }
}
