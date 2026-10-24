/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.xr.mapping

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import com.multiset.sdk.mapping.MappingLocationProvider
import com.multiset.sdk.model.GeoCoordinates

/** Geo-tags a scan from the platform location service; it never asks for the permission. */
class AndroidLocationProvider(private val context: Context) : MappingLocationProvider {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var latest: Location? = null
    private var listening = false

    private val listener = LocationListenerCompat { latest = it }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (listening) return
        val provider = providerFor(Build.VERSION.SDK_INT, granted(FINE), granted(COARSE)) ?: return
        try {
            if (!manager.isProviderEnabled(provider)) return
            latest = manager.getLastKnownLocation(provider)
            manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            listening = true
        } catch (_: SecurityException) {
            listening = false
        } catch (_: IllegalArgumentException) {
            listening = false
        }
    }

    override fun stop() {
        if (!listening) return
        manager.removeUpdates(listener)
        listening = false
    }

    override fun currentFix(): GeoCoordinates? = latest?.let(::coordinates)

    /**
     * [currentFix] if it is at most [maxAgeMs] old. A localization geo hint narrows the search to
     * where the fix says, so the last known location from hours ago must not be sent as one.
     */
    fun currentFix(maxAgeMs: Long): GeoCoordinates? = latest
        ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= maxAgeMs * 1_000_000 }
        ?.let(::coordinates)

    private fun coordinates(location: Location) =
        GeoCoordinates(location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION

        /**
         * The location provider the granted permissions allow on [sdkInt], or null without either.
         * The fused provider serves coarse fixes from S on; before it, GPS needs fine location.
         */
        @SuppressLint("InlinedApi") // FUSED_PROVIDER only for sdkInt >= S; start() passes SDK_INT
        fun providerFor(sdkInt: Int, fine: Boolean, coarse: Boolean): String? = when {
            !fine && !coarse -> null
            sdkInt >= Build.VERSION_CODES.S -> LocationManager.FUSED_PROVIDER
            fine -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
    }
}
