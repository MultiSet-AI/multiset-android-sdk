/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.ui.runstatus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withScale
import com.multiset.xr.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** True when the user turned animations off in developer options, the closest to reduce-motion. */
internal fun Context.animationsDisabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The 36 dp round card a run reports on, step by step: a flash for each frame taken, an arc
 * chasing round the rim while they are sent, a spinner while the server searches, then a tick or
 * a mark. Hidden collapses the view to GONE after a fade.
 */
class RunStatusCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val sizePx = DIAMETER_DP * density
    private val fill = paint(Paint.Style.FILL, R.color.ar_card_scrim)
    private val border = paint(Paint.Style.STROKE, R.color.ar_track).apply { strokeWidth = density }
    private val glyph = paint(Paint.Style.FILL, R.color.ar_on)
    private val glyphStroke = paint(Paint.Style.STROKE, R.color.ar_on).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spinner = paint(Paint.Style.STROKE, R.color.ar_on_mid).apply {
        strokeWidth = SPINNER_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val ringTrack = paint(Paint.Style.STROKE, R.color.ar_accent).apply {
        strokeWidth = RING_DP * density
        alpha = (RING_TRACK_ALPHA * ALPHA_MAX).toInt()
    }
    private val ringArc = paint(Paint.Style.STROKE, R.color.ar_accent).apply {
        strokeWidth = RING_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val good = paint(Paint.Style.FILL, R.color.ar_good)
    private val bad = paint(Paint.Style.FILL, R.color.ar_bad)
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bounds = RectF()
    private val camera = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_run_status_camera)).mutate().apply {
        setTint(ContextCompat.getColor(context, R.color.ar_on))
    }

    private var state: RunStatusState = RunStatusState.Hidden
    private var flashStartedMs = NO_FLASH
    private val flashInterpolator = DecelerateInterpolator()
    private val clickInterpolator = OvershootInterpolator(CLICK_OVERSHOOT)

    init {
        visibility = GONE
    }

    fun setState(state: RunStatusState) {
        val previous = this.state
        if (state == previous) return
        this.state = state
        contentDescription = descriptionFor(state)
        if (state is RunStatusState.Capturing && shotTaken(previous, state)) {
            flashStartedMs = SystemClock.uptimeMillis()
        }
        if (state == RunStatusState.Hidden) fadeOut() else fadeIn(previous == RunStatusState.Hidden)
        invalidate()
    }

    private fun shotTaken(previous: RunStatusState, state: RunStatusState.Capturing): Boolean {
        if (state.shots <= 0) return false
        return previous !is RunStatusState.Capturing || previous.shots != state.shots
    }

    private fun fadeIn(wasHidden: Boolean) {
        animate().cancel()
        if (!wasHidden && visibility == VISIBLE) {
            alpha = 1f
            return
        }
        visibility = VISIBLE
        if (context.animationsDisabled()) {
            alpha = 1f
        } else {
            alpha = 0f
            animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun fadeOut() {
        animate().cancel()
        if (visibility != VISIBLE || context.animationsDisabled()) {
            visibility = GONE
            return
        }
        animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (state == RunStatusState.Hidden) visibility = GONE
        }.start()
    }

    private fun descriptionFor(state: RunStatusState): String? = when (state) {
        RunStatusState.Hidden -> null
        RunStatusState.Working -> context.getString(R.string.run_status_working)
        is RunStatusState.Capturing -> context.getString(R.string.run_status_capturing)
        RunStatusState.Uploading -> context.getString(R.string.run_status_uploading)
        RunStatusState.Succeeded -> context.getString(R.string.run_status_succeeded)
        RunStatusState.Failed -> context.getString(R.string.run_status_failed)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val px = sizePx.toInt()
        setMeasuredDimension(px, px)
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val still = context.animationsDisabled()
        val now = SystemClock.uptimeMillis()
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, cx, fill)
        canvas.drawCircle(cx, cy, cx - border.strokeWidth / 2f, border)
        var animating = false
        when (val current = state) {
            RunStatusState.Hidden -> Unit
            RunStatusState.Working -> {
                drawSpinner(canvas, cx, cy, if (still) 0f else (now % SPIN_MS) / SPIN_MS.toFloat())
                animating = !still
            }
            is RunStatusState.Capturing -> animating = drawShutter(canvas, cx, cy, current, now, still)
            RunStatusState.Uploading -> {
                drawUploadRing(canvas, cx, cy, if (still) 0f else (now % RING_MS) / RING_MS.toFloat())
                drawUploadGlyph(canvas, cx, cy, if (still) 0.5f else (now % UPLOAD_MS) / UPLOAD_MS.toFloat(), still)
                animating = !still
            }
            RunStatusState.Succeeded -> drawResult(canvas, cx, cy, good, tick = true)
            RunStatusState.Failed -> drawResult(canvas, cx, cy, bad, tick = false)
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, turn: Float) {
        val r = SPINNER_RADIUS_DP * density
        bounds.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(bounds, -90f + 360f * turn, SPINNER_SWEEP_DEG, false, spinner)
    }

    private fun drawShutter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        current: RunStatusState.Capturing,
        now: Long,
        still: Boolean,
    ): Boolean {
        var animating = false
        val elapsed = now - flashStartedMs
        val firing = !still && current.shots > 0 && flashStartedMs != NO_FLASH
        if (firing && elapsed < FLASH_MS) {
            val t = elapsed / FLASH_MS.toFloat()
            flash.alpha = ((FLASH_PEAK * (1f - flashInterpolator.getInterpolation(t))) * ALPHA_MAX).toInt()
            canvas.drawCircle(cx, cy, cx - density, flash)
            animating = true
        }
        var scale = 1f
        if (firing && elapsed < CLICK_MS) {
            val t = clickInterpolator.getInterpolation(elapsed / CLICK_MS.toFloat())
            scale = PRESSED_SCALE + (1f - PRESSED_SCALE) * t
            animating = true
        }
        canvas.withScale(scale, scale, cx, cy) { drawCamera(this, cx, cy) }
        return animating
    }

    private fun drawCamera(canvas: Canvas, cx: Float, cy: Float) {
        val half = (CAMERA_DP * density / 2f).toInt()
        camera.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        camera.draw(canvas)
    }

    private fun drawUploadRing(canvas: Canvas, cx: Float, cy: Float, turn: Float) {
        val r = cx - RING_DP * density / 2f
        bounds.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(bounds, 0f, FULL_TURN_DEG, false, ringTrack)
        canvas.drawArc(bounds, -90f + FULL_TURN_DEG * turn, RING_ARC * FULL_TURN_DEG, false, ringArc)
    }

    private fun drawUploadGlyph(canvas: Canvas, cx: Float, cy: Float, phase: Float, still: Boolean) {
        val size = GLYPH_DP * density
        val alpha = if (still) 1f else min(1f, UPLOAD_FADE_GAIN * sin(PI.toFloat() * phase))
        val offset = size * UPLOAD_TRAVEL * (1f - 2f * phase)
        val baseY = cy + size * 0.38f
        val arrowCy = cy - size * 0.1f + offset
        val half = size * 0.3f
        glyphStroke.strokeWidth = size * 0.14f
        glyphStroke.alpha = (alpha.coerceAtLeast(0f) * ALPHA_MAX).toInt()
        canvas.drawLine(cx, arrowCy + half, cx, arrowCy - half, glyphStroke)
        canvas.drawLine(cx - half * 0.8f, arrowCy - half * 0.2f, cx, arrowCy - half, glyphStroke)
        canvas.drawLine(cx + half * 0.8f, arrowCy - half * 0.2f, cx, arrowCy - half, glyphStroke)
        glyphStroke.alpha = ALPHA_MAX.toInt()
        val lineW = size * 0.78f
        val lineH = maxOf(2f * density, size * 0.12f)
        bounds.set(cx - lineW / 2f, baseY - lineH / 2f, cx + lineW / 2f, baseY + lineH / 2f)
        canvas.drawRoundRect(bounds, lineH / 2f, lineH / 2f, glyph)
    }

    private fun drawResult(canvas: Canvas, cx: Float, cy: Float, disc: Paint, tick: Boolean) {
        val size = GLYPH_DP * density
        canvas.drawCircle(cx, cy, size / 2f, disc)
        glyphStroke.strokeWidth = size * 0.13f
        if (tick) {
            canvas.drawLine(cx - size * 0.22f, cy + size * 0.01f, cx - size * 0.06f, cy + size * 0.17f, glyphStroke)
            canvas.drawLine(cx - size * 0.06f, cy + size * 0.17f, cx + size * 0.24f, cy - size * 0.15f, glyphStroke)
        } else {
            canvas.drawLine(cx, cy - size * 0.24f, cx, cy + size * 0.06f, glyphStroke)
            canvas.drawCircle(cx, cy + size * 0.22f, size * 0.075f, glyph)
        }
    }

    private fun paint(style: Paint.Style, colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        color = ContextCompat.getColor(context, colorRes)
    }

    private companion object {
        const val DIAMETER_DP = 36f
        const val GLYPH_DP = 14f
        const val RING_DP = 2.5f
        const val RING_TRACK_ALPHA = 0.22f
        const val RING_ARC = 0.28f
        const val RING_MS = 1000L
        const val UPLOAD_MS = 900L
        const val UPLOAD_TRAVEL = 0.28f
        const val UPLOAD_FADE_GAIN = 2.5f
        const val SPINNER_RADIUS_DP = 6f
        const val SPINNER_STROKE_DP = 2f
        const val SPINNER_SWEEP_DEG = 270f
        const val SPIN_MS = 900L
        const val FULL_TURN_DEG = 360f
        const val FADE_MS = 250L
        const val FLASH_MS = 450L
        const val FLASH_PEAK = 0.85f
        const val CLICK_MS = 300L
        const val CLICK_OVERSHOOT = 2f
        const val PRESSED_SCALE = 0.75f
        // The Material icon leaves 2 of its 24 units clear on each side; 18 dp draws a ~15 dp camera.
        const val CAMERA_DP = 18f
        const val ALPHA_MAX = 255f
        const val NO_FLASH = -1L
    }
}
