package com.example.gpstovss.speed

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import android.os.SystemClock

class GpsSpeedProvider(
    private val context: Context,
    private val fusedClient: FusedLocationProviderClient,
    private val onDebug: (String) -> Unit
) : SpeedProvider {

    private var callback: LocationCallback? = null

    @Volatile private var lastMps: Float = 0f
    @Volatile private var lastFixMs: Long = 0L
    @Volatile private var gpsHzEma: Float = 0f
    private val hzAlpha = 0.2f

    override fun start() {
        if (callback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            100L
        )
            .setMinUpdateIntervalMillis(100L)
            .setWaitForAccurateLocation(false)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastMps = loc.speed.coerceAtLeast(0f)
                val nowMs = SystemClock.elapsedRealtime()
                if (lastFixMs != 0L) {
                    val dt = (nowMs - lastFixMs).coerceAtLeast(1L)
                    val hzInstant = 1000f / dt.toFloat()
                    gpsHzEma = if (gpsHzEma == 0f) hzInstant else (hzAlpha * hzInstant + (1f - hzAlpha) * gpsHzEma)
                }
                lastFixMs = nowMs
                // Optional debug hook
                ///onDebug("gps_mps=%.2f hasBearing=%s".format(lastMps, loc.hasBearing()))
            }
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onDebug("gps_start_blocked(no_fine_location)")
            return
        }

        fusedClient.requestLocationUpdates(request, callback!!, context.mainLooper)
        onDebug("gps_started")
    }

    override fun stop() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
        onDebug("gps_stopped")
    }

    override fun latest(): SpeedSample {
        val rawMph = lastMps * 2.23694f
        val mph = if (rawMph < 0.2f) 0f else rawMph // keep your deadband
        return SpeedSample(
            speedMph = mph,
            rawMph = rawMph,
            source = "GPS_HZ: %.1f".format(gpsHzEma)
        )
    }
}
