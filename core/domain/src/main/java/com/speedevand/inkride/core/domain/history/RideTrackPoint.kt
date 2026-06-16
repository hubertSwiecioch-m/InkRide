package com.speedevand.inkride.core.domain.history

/**
 * A single recorded GPS position along a ride, persisted so the full track can
 * be exported as GPX. [altitudeM] is the fused (barometer/GPS) altitude at the
 * time of the fix; both it and [accuracyM] may be null when unavailable.
 */
data class RideTrackPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null
)
