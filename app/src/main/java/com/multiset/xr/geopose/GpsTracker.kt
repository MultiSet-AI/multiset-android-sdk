/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.geopose

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import com.multiset.sdk.model.GeoCoordinates
import com.multiset.xr.mapping.AndroidLocationProvider

/** Device GPS fixes for the blue track and the optional geo hint. It never asks for the permission. */
class GpsTracker(private val context: Context, private val onFix: (GpsFix) -> Unit) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var latest: Location? = null
    private var listening = false

    private val listener = LocationListenerCompat { deliver(it) }

    val hasPermission: Boolean get() = granted(FINE) || granted(COARSE)

    val coarseOnly: Boolean get() = !granted(FINE) && granted(COARSE)

    /** Whether fixes are arriving: false without a permission or with location switched off. */
    val isListening: Boolean get() = listening

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening) return
        val provider = AndroidLocationProvider.providerFor(android.os.Build.VERSION.SDK_INT, granted(FINE), granted(COARSE))
            ?: return
        try {
            if (!manager.isProviderEnabled(provider)) return
            manager.getLastKnownLocation(provider)?.let(::deliver)
            manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            listening = true
        } catch (_: SecurityException) {
            listening = false
        } catch (_: IllegalArgumentException) {
            listening = false
        }
    }

    fun stop() {
        if (!listening) return
        manager.removeUpdates(listener)
        listening = false
    }

    /** The latest fix if it is at most [maxAgeMs] old: an hours-old one must not narrow a query. */
    fun currentCoordinates(maxAgeMs: Long): GeoCoordinates? = latest
        ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= maxAgeMs * 1_000_000 }
        ?.let { GeoCoordinates(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else null) }

    private fun deliver(location: Location) {
        latest = location
        onFix(
            GpsFix(
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                fixTimestampMs = location.time,
            )
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    }
}
