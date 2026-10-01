package com.example.ftnnavigation.poc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import com.example.ftnnavigation.campus.CampusGeo
import com.example.ftnnavigation.campus.GpsFix

/**
 * Lokacija od [start] do [stop], u metrima kampusa ([CampusGeo]). Sistemski `LocationManager`
 * (bez Play Services zavisnosti): fused provider ako ga telefon ima (GPS + Wi-Fi + mreža), inače
 * GPS. Lokacije stižu na glavnoj niti, oko jednom u sekundi. [recorder] (debug) snima svaku lokaciju.
 */
class GpsSession(
    private val context: Context,
    private val recorder: GpsRecorder? = null,
    private val onFix: (GpsFix) -> Unit,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var started = false

    private val listener = LocationListener(::deliver)

    private fun deliver(location: Location) {
        val fix = location.toFix()
        recorder?.location(location, fix)
        onFix(fix)
    }

    /** Pokreće lokaciju ako je dozvoljena; false ako nije (dozvola ili nema providera). */
    @SuppressLint("MissingPermission") // proverava se u hasPermission
    fun start(): Boolean {
        if (started) return true
        if (!hasPermission(context)) return false
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { locationManager.hasProvider(it) } ?: return false
        recorder?.started(SystemClock.elapsedRealtimeNanos(), provider)
        // Skorašnja poznata lokacija odmah, da tačka ne čeka prvi fix.
        locationManager.getLastKnownLocation(provider)
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < MAX_LAST_KNOWN_AGE_NS }
            ?.let(::deliver)
        val request = LocationRequest.Builder(INTERVAL_MS)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .build()
        locationManager.requestLocationUpdates(provider, request, context.mainExecutor, listener)
        started = true
        return true
    }

    fun stop() {
        if (!started) return
        locationManager.removeUpdates(listener)
        recorder?.stopped(SystemClock.elapsedRealtimeNanos())
        started = false
    }

    private fun Location.toFix() = GpsFix(
        CampusGeo.toCampus(latitude, longitude),
        accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        elapsedNs = elapsedRealtimeNanos,
    )

    companion object {
        private const val INTERVAL_MS = 1000L
        private const val MAX_LAST_KNOWN_AGE_NS = 30_000_000_000L

        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        /** Precizna lokacija je dozvoljena (samo približna - ~2 km - ne pomaže na kampusu). */
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
