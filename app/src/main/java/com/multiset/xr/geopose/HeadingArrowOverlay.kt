/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.geopose

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import androidx.core.graphics.withTranslation
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * The latest GeoPose as an arrow: its tip points along the compass heading and its notch sits on
 * the position. Drawn here rather than as a rotated Marker, whose rotation runs counter-clockwise.
 */
class HeadingArrowOverlay(density: Float) : Overlay() {

    private var position: GeoPoint? = null
    private var headingDegrees = 0f

    private val pixel = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    // Both paths point up, which is north before the canvas is turned by the heading.
    private val arrow = Path().apply {
        moveTo(0f, -18f * density)
        lineTo(11f * density, 12f * density)
        lineTo(0f, 5f * density)
        lineTo(-11f * density, 12f * density)
        close()
    }
    private val cone = Path().apply {
        moveTo(0f, 0f)
        lineTo(-26f * density, -58f * density)
        lineTo(26f * density, -58f * density)
        close()
    }

    /** [headingDegrees] is clockwise from true north; null hides the arrow. */
    fun show(position: GeoPoint, headingDegrees: Double?, color: Int) {
        this.position = position.takeIf { headingDegrees != null }
        this.headingDegrees = headingDegrees?.toFloat() ?: 0f
        fill.color = color
        beam.color = Color.argb(56, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun hide() {
        position = null
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val at = position ?: return
        projection.toPixels(at, pixel)
        canvas.withTranslation(pixel.x.toFloat(), pixel.y.toFloat()) {
            // The canvas already carries the map's own rotation, so north on it is the map's north.
            rotate(headingDegrees)
            drawPath(cone, beam)
            drawPath(arrow, fill)
            drawPath(arrow, outline)
        }
    }
}
