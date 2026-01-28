package com.example.gpstovss.fusion

import kotlin.math.*

/**
 * Autocalibration using:
 * - Stoplights: learn accel bias + define "stopped"
 * - Motion w/ GPS bearing: define forward direction in world frame
 */
class AutoCalibratedAccel {
    // World-frame accel bias learned while stopped
    private var bx = 0f
    private var by = 0f
    private var bz = 0f

    // Heading unit vector in world frame (x=east, y=north)
    private var hx = 0f
    private var hy = 1f
    private var haveHeading = false

    // Stop detector
    private var stoppedSinceMs: Long = 0L
    private var stopped = false

    // Tuning
    var stopSpeedMps = 0.25f        // ~0.56 mph
    var stopHoldMs = 1200L          // must be still this long
    var stopAccelRms = 0.15f        // stillness threshold
    var biasLearnAlpha = 0.02f      // EMA learning rate

    fun onGps(speedMps: Float, bearingDeg: Float?) {
        // Only trust bearing at decent speed; low-speed bearing is chaos
        if (bearingDeg != null && speedMps > 2.0f) { // ~4.5 mph
            val rad = Math.toRadians(bearingDeg.toDouble())
            hx = sin(rad).toFloat()
            hy = cos(rad).toFloat()
            haveHeading = true
        }
    }

    fun updateStopState(nowMs: Long, gpsSpeedMps: Float, axW: Float, ayW: Float, azW: Float) {
        val mag = sqrt(axW * axW + ayW * ayW + azW * azW)
        val stoppedNow = (gpsSpeedMps < stopSpeedMps) && (mag < stopAccelRms)

        if (stoppedNow) {
            if (!stopped) {
                if (stoppedSinceMs == 0L) stoppedSinceMs = nowMs
                if (nowMs - stoppedSinceMs >= stopHoldMs) stopped = true
            }
        } else {
            stoppedSinceMs = 0L
            stopped = false
        }
    }

    fun isStopped(): Boolean = stopped

    fun learnBiasIfStopped(axW: Float, ayW: Float, azW: Float) {
        if (!stopped) return
        bx = (1f - biasLearnAlpha) * bx + biasLearnAlpha * axW
        by = (1f - biasLearnAlpha) * by + biasLearnAlpha * ayW
        bz = (1f - biasLearnAlpha) * bz + biasLearnAlpha * azW
    }

    fun phoneToWorld(R: FloatArray, axP: Float, ayP: Float, azP: Float): FloatArray {
        // World = R * phone
        val axW = R[0] * axP + R[1] * ayP + R[2] * azP
        val ayW = R[3] * axP + R[4] * ayP + R[5] * azP
        val azW = R[6] * axP + R[7] * ayP + R[8] * azP
        return floatArrayOf(axW, ayW, azW)
    }

    fun forwardAccelMps2(axW: Float, ayW: Float, azW: Float): Float {
        if (!haveHeading) return 0f
        val ax = axW - bx
        val ay = ayW - by
        return ax * hx + ay * hy // horizontal projection along travel direction
    }
}
