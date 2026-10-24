/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping.pointcloud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Transparent overlay that draws the scan's coverage cloud over the camera feed as glowing dots. */
class MapPointCloudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), MapPointSink {

    private val world = FloatArray(PointCloudAccumulator.CAPACITY * 3)
    private val screen = FloatArray(PointCloudAccumulator.CAPACITY * 2)
    private var worldCount = 0
    private var screenCount = 0
    private var view: FloatArray? = null
    private var projection: FloatArray? = null

    private val glow = dotPaint(GLOW_COLOR, GLOW_DP)
    private val core = dotPaint(CORE_COLOR, CORE_DP)

    override fun sync(accumulator: PointCloudAccumulator) {
        worldCount = minOf(accumulator.size, world.size / 3)
        accumulator.positions.copyInto(world, endIndex = worldCount * 3)
        reproject()
        invalidate()
    }

    /** Stores ARCore's camera [view] and [projection] matrices (column-major) and redraws. */
    fun setCamera(view: FloatArray, projection: FloatArray) {
        this.view = view.copyOf()
        this.projection = projection.copyOf()
        reproject()
        invalidate()
    }

    fun clear() {
        worldCount = 0
        screenCount = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        reproject()
    }

    override fun onDraw(canvas: Canvas) {
        if (screenCount == 0) return
        canvas.drawPoints(screen, 0, screenCount * 2, glow)
        canvas.drawPoints(screen, 0, screenCount * 2, core)
    }

    private fun reproject() {
        val view = view
        val projection = projection
        screenCount = if (view == null || projection == null || width == 0 || height == 0) 0 else
            PointProjection.project(world, worldCount, view, projection, width, height, screen)
    }

    private fun dotPaint(color: Int, diameterDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeCap = Paint.Cap.ROUND
        strokeWidth = diameterDp * resources.displayMetrics.density
    }

    private companion object {
        val GLOW_COLOR = Color.argb(0xFF, 0xFF, 0xC2, 0x1A)
        val CORE_COLOR = Color.argb(0xFF, 0xFF, 0xF7, 0xC2)
        const val GLOW_DP = 5f
        const val CORE_DP = 2f
    }
}
