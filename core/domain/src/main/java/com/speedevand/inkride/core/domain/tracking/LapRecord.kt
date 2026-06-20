package com.speedevand.inkride.core.domain.tracking

/**
 * A single completed lap, captured as a delta against the ride totals at the
 * previous lap boundary. Laps are recorded on demand (manual lap button) and the
 * in-progress segment is closed as a final lap when the ride stops.
 */
data class LapRecord(
    val lapNumber: Int,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val averageSpeedKmh: Double,
    val elevationGainM: Double
)
