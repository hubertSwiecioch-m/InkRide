package com.speedevand.inkride.core.domain.tracking

/**
 * A single sensor reading fed to [RideMetricsCalculator].
 *
 * [bearingDegrees], when non-null, is a finite value normalized to `[0, 360)`
 * (sanitized at the data source). All other GPS fields are null when the
 * underlying fix is stale or too inaccurate to trust.
 */
data class RideSensorSample(
    val timestampMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeFromGpsM: Double? = null,
    val altitudeFromBarometerM: Double? = null,
    val speedFromGpsMps: Double? = null,
    val accuracyM: Float? = null,
    val bearingDegrees: Float? = null,
    val satelliteCount: Int? = null,
    // Raw barometric pressure in hectopascals (null when no barometer). Used for
    // the local weather-trend hint; altitude fusion uses the derived
    // [altitudeFromBarometerM] instead.
    val pressureHpa: Double? = null,
)
