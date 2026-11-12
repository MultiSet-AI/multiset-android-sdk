/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.geopose

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Compass and pose arithmetic for a localization GeoPose. No Android, so the JVM tests pin it. */
object GeoPoseMath {

    const val FRAME_Y_UP_ENU = "Y-Up-ENU"
    const val FRAME_ENU = "ENU"

    // Below this level length the camera looks too far up or down for its forward to give a heading.
    private const val MIN_LEVEL_FORWARD = 0.35

    private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /**
     * The compass heading the camera faces, in degrees clockwise from true north, [0, 360).
     *
     * `Y-Up-ENU` (+X east, +Y up, +Z north), the service's default, carries a Unity-style camera that
     * looks along its +Z; `ENU` (+X east, +Y north, +Z up) carries an ARCore-style one that looks along
     * its -Z. An unnamed frame is read as `Y-Up-ENU`. Null when the quaternion is degenerate.
     */
    fun compassHeading(x: Double, y: Double, z: Double, w: Double, frameName: String?): Double? {
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (norm < 1e-9) return null
        val qx = x / norm; val qy = y / norm; val qz = z / norm; val qw = w / norm

        val enu = frameName.equals(FRAME_ENU, ignoreCase = true)
        val cameraZ = Axis.inFrame(enu, 2.0 * (qx * qz + qw * qy), 2.0 * (qy * qz - qw * qx), 1.0 - 2.0 * (qx * qx + qy * qy))
        val forward = if (enu) cameraZ.reversed() else cameraZ
        if (hypot(forward.east, forward.north) >= MIN_LEVEL_FORWARD) return bearing(forward.east, forward.north)

        // Looking down, the top of the image points where the user faces; looking up, the bottom does.
        val top = Axis.inFrame(enu, 2.0 * (qx * qy - qw * qz), 1.0 - 2.0 * (qx * qx + qz * qz), 2.0 * (qy * qz + qw * qx))
        val towards = if (forward.up < 0) 1.0 else -1.0
        return if (hypot(top.east, top.north) < 1e-6) null else bearing(towards * top.east, towards * top.north)
    }

    /** Both of the service's frames give a heading; an unnamed frame is taken as `Y-Up-ENU`. */
    fun isHeadingFrame(frameName: String?): Boolean =
        frameName == null ||
            frameName.equals(FRAME_Y_UP_ENU, ignoreCase = true) ||
            frameName.equals(FRAME_ENU, ignoreCase = true)

    fun cardinal(headingDegrees: Double): String =
        CARDINALS[(Math.floorMod(Math.round(headingDegrees / 45.0), 8L)).toInt()]

    /** Whether the fix has a place on Earth; a map that is not georeferenced answers 0, 0 or nothing. */
    fun isOnEarth(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && (latitude != 0.0 || longitude != 0.0) &&
            abs(latitude) <= 90.0 && abs(longitude) <= 180.0

    /** ARGB colour of a fix's marker: red under 0.4 confidence, amber under 0.7, else green. */
    fun confidenceColor(confidence: Float?): Int = when {
        confidence == null || confidence < 0.4f -> 0xFFE53935.toInt()
        confidence < 0.7f -> 0xFFFFB300.toInt()
        else -> 0xFF43A047.toInt()
    }

    private fun bearing(east: Double, north: Double): Double {
        val degrees = Math.toDegrees(atan2(east, north))
        return ((degrees % 360.0) + 360.0) % 360.0
    }

    private class Axis(val east: Double, val north: Double, val up: Double) {
        fun reversed() = Axis(-east, -north, -up)

        companion object {
            // A rotated camera axis (x, y, z) in the frame's own coordinates.
            fun inFrame(enu: Boolean, x: Double, y: Double, z: Double) =
                if (enu) Axis(east = x, north = y, up = z) else Axis(east = x, north = z, up = y)
        }
    }
}
