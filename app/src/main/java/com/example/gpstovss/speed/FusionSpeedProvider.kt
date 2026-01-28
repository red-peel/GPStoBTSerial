package com.example.gpstovss.speed

/**
 * Placeholder for the future sensor-fusion version.
 * For now it's intentionally disconnected from GPS.
 */
class FusionSpeedProvider(
    private val onDebug: (String) -> Unit
) : SpeedProvider {

    override fun start() {
        onDebug("fusion_started(DISABLED)")
    }

    override fun stop() {
        onDebug("fusion_stopped(DISABLED)")
    }

    override fun latest(): SpeedSample {
        // Return zeros until you wire it later
        return SpeedSample(
            speedMph = 0f,
            rawMph = 0f,
            source = "FUSION(DISABLED)"
        )
    }
}
