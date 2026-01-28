package com.example.gpstovss.fusion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GPS-corrected accel integration (complementary filter).
 *
 * - Accel predicts speed between GPS fixes (responsive)
 * - GPS corrects drift over time (accurate)
 */
class SpeedEstimator(
    private val accelTrust: Float = 0.85f,     // 0.75–0.9 typical
    private val accelDeadbandMps2: Float = 0.08f,
    private val accelClampMps2: Float = 6.0f
) {
    private var vMps: Float = 0f
    private var lastAccelNs: Long = 0L

    fun resetToZero() {
        vMps = 0f
        lastAccelNs = 0L
    }

    fun onAccel(forwardAccMps2: Float, timestampNs: Long): Float {
        if (lastAccelNs == 0L) {
            lastAccelNs = timestampNs
            return vMps
        }

        val dt = (timestampNs - lastAccelNs) / 1_000_000_000f
        lastAccelNs = timestampNs
        if (dt <= 0f || dt > 0.5f) return vMps

        var a = forwardAccMps2
        if (abs(a) < accelDeadbandMps2) a = 0f
        a = max(-accelClampMps2, min(accelClampMps2, a))

        vMps = max(0f, vMps + a * dt)
        return vMps
    }

    fun onGps(gpsSpeedMps: Float): Float {
        val gps = max(0f, gpsSpeedMps)
        vMps = accelTrust * vMps + (1f - accelTrust) * gps
        return vMps
    }

    fun getSpeedMps(): Float = vMps
}
