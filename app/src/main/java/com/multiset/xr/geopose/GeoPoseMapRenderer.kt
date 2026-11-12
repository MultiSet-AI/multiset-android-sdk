/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.geopose

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import com.multiset.xr.R
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws the device GPS track (blue) and the GeoPose track (green, dashed) on the map. Each GeoPose
 * is a confidence-coloured dot; the latest one is the heading arrow.
 */
class GeoPoseMapRenderer(private val context: Context, private val mapView: MapView) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val density = context.resources.displayMetrics.density

    private val gpsLine = Polyline(mapView).apply {
        outlinePaint.color = ContextCompat.getColor(context, R.color.track_gps)
        outlinePaint.strokeWidth = 4f * density
    }
    private val geoPoseLine = Polyline(mapView).apply {
        outlinePaint.color = ContextCompat.getColor(context, R.color.track_geopose)
        outlinePaint.strokeWidth = 3f * density
        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val accuracyCircle = Polygon(mapView).apply {
        fillPaint.color = ContextCompat.getColor(context, R.color.track_gps_fill)
        outlinePaint.color = ContextCompat.getColor(context, R.color.track_gps)
        outlinePaint.strokeWidth = density
    }
    private val gpsMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = dot(ContextCompat.getColor(context, R.color.track_gps), sizeDp = 16, strokeDp = 2)
        title = context.getString(R.string.geopose_marker_gps)
    }
    private val headingArrow = HeadingArrowOverlay(density)

    private val geoPoseMarkers = mutableListOf<Marker>()
    private var drawnTrack: List<GeoPosePoint>? = null
    private var gpsMarkerShown = false

    init {
        mapView.overlays += listOf(accuracyCircle, gpsLine, geoPoseLine)
    }

    fun render(state: GeoPoseMapState) {
        gpsLine.setPoints(state.gpsTrack.map { GeoPoint(it.latitude, it.longitude) })
        drawGps(state.latestGps)
        drawGeoPoses(state.geoPoseTrack)
        mapView.invalidate()

        if (state.followMode) target(state)?.let { mapView.controller.animateTo(it) }
    }

    fun target(state: GeoPoseMapState): GeoPoint? =
        state.latestGeoPose?.let { GeoPoint(it.latitude, it.longitude) }
            ?: state.latestGps?.let { GeoPoint(it.latitude, it.longitude) }

    private fun drawGps(fix: GpsFix?) {
        val accuracy = fix?.horizontalAccuracy
        accuracyCircle.points = if (fix != null && accuracy != null && accuracy > 0) {
            Polygon.pointsAsCircle(GeoPoint(fix.latitude, fix.longitude), accuracy)
        } else {
            emptyList()
        }
        if (fix == null) {
            if (gpsMarkerShown) mapView.overlays.remove(gpsMarker)
            gpsMarkerShown = false
            return
        }
        gpsMarker.position = GeoPoint(fix.latitude, fix.longitude)
        gpsMarker.snippet = gpsSnippet(fix)
        if (!gpsMarkerShown) mapView.overlays.add(gpsMarker)
        gpsMarkerShown = true
    }

    // GPS fixes arrive every second; the GeoPose markers are rebuilt only when their track changes.
    private fun drawGeoPoses(track: List<GeoPosePoint>) {
        if (track === drawnTrack) return
        drawnTrack = track

        mapView.overlays.removeAll(geoPoseMarkers)
        mapView.overlays.remove(headingArrow)
        geoPoseMarkers.clear()
        geoPoseLine.setPoints(track.map { GeoPoint(it.latitude, it.longitude) })

        val latest = track.lastOrNull()
        track.forEach { point ->
            val isLatest = point === latest
            val showsArrow = isLatest && point.headingDegrees != null
            geoPoseMarkers += Marker(mapView).apply {
                position = GeoPoint(point.latitude, point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // Under the arrow the dot stays as an invisible tap target for the info bubble.
                icon = dot(
                    if (showsArrow) Color.TRANSPARENT else GeoPoseMath.confidenceColor(point.confidence),
                    sizeDp = if (isLatest) 14 else 9,
                    strokeDp = if (showsArrow) 0 else 1,
                )
                title = context.getString(if (isLatest) R.string.geopose_marker_latest else R.string.geopose_marker_fix)
                snippet = geoPoseSnippet(point)
            }
        }
        mapView.overlays.addAll(geoPoseMarkers)

        if (latest == null) {
            headingArrow.hide()
        } else {
            headingArrow.show(
                GeoPoint(latest.latitude, latest.longitude),
                latest.headingDegrees,
                GeoPoseMath.confidenceColor(latest.confidence),
            )
            mapView.overlays.add(headingArrow)
        }
    }

    private fun dot(color: Int, sizeDp: Int, strokeDp: Int): Drawable {
        val size = (sizeDp * density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeDp > 0) setStroke((strokeDp * density).toInt(), Color.WHITE)
            setSize(size, size)
            setBounds(0, 0, size, size)
        }
    }

    private fun gpsSnippet(fix: GpsFix): String {
        val accuracy = fix.horizontalAccuracy?.let { " · ±%.1f m".format(Locale.US, it) }.orEmpty()
        return "%.6f, %.6f%s\n%s".format(
            Locale.US, fix.latitude, fix.longitude, accuracy, timeFormat.format(Date(fix.fixTimestampMs)),
        )
    }

    private fun geoPoseSnippet(point: GeoPosePoint): String {
        val confidence = point.confidence?.let { " · conf %.2f".format(Locale.US, it) }.orEmpty()
        val heading = point.headingDegrees
            ?.let { " · %.0f° %s".format(Locale.US, it, GeoPoseMath.cardinal(it)) }.orEmpty()
        return "%.6f, %.6f%s%s\n%s".format(
            Locale.US, point.latitude, point.longitude, confidence, heading, timeFormat.format(Date(point.timestampMs)),
        )
    }
}
