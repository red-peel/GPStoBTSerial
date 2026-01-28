package com.example.gpstovss.speed

data class SpeedSample(
    val speedMph: Float,     // the "main" speed (what we display / transmit)
    val rawMph: Float,       // raw source speed (for debugging)
    val source: String       // "GPS", "FUSION", etc.
)

interface SpeedProvider {
    fun start()
    fun stop()
    fun latest(): SpeedSample
}
