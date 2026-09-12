package com.drishti.app.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.drishti.app.net.MonitorLocation

/**
 * Latest GPS/network fix while Walk Mode is active. No background permission
 * is requested: the foreground service starts and stops this tracker together
 * with the camera session.
 */
class WalkLocationTracker(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)

    @Volatile private var latest: MonitorLocation? = null
    @Volatile private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            accept(location)
        }

        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun snapshot(): MonitorLocation? = latest

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            if (!manager.isProviderEnabled(provider)) return@forEach
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let(::accept)
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_METRES,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager.removeUpdates(listener) }
    }

    private fun accept(location: Location) {
        val candidate = MonitorLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            observedAtMs = location.time.coerceAtLeast(0L),
        )
        val existing = latest
        if (existing == null || candidate.observedAtMs >= existing.observedAtMs) latest = candidate
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val UPDATE_INTERVAL_MS = 2_000L
        const val MIN_DISTANCE_METRES = 1f
    }
}
