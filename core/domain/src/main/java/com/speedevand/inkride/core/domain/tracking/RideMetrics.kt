package com.speedevand.inkride.core.domain.tracking

enum class GpsQuality {
    GOOD,
    FAIR,
    POOR,
}

data class RideMetrics(
    val currentSpeedKmh: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val distanceKm: Double = 0.0,
    val movingTimeSeconds: Long = 0L,
    val elapsedTimeSeconds: Long = 0L,
    val altitudeM: Double? = null,
    val elevationGainM: Double = 0.0,
    val gradePercent: Double = 0.0,
    val caloriesKcal: Double = 0.0,
    val powerWatts: Int = 0,
    val averagePowerWatts: Int = 0,
    val gpsAccuracyM: Float? = null,
    val bearingDegrees: Float? = null,
    val gpsQuality: GpsQuality = GpsQuality.POOR,
    // Live values from paired BLE sensors; null when no sensor is connected.
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    // Local weather hint from the barometric pressure trend (offline, no API).
    val weatherTrend: WeatherTrend = WeatherTrend.UNKNOWN,
)
