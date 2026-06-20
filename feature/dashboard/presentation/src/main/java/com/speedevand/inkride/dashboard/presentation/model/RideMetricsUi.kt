package com.speedevand.inkride.dashboard.presentation.model

import com.speedevand.inkride.core.toClockString
import com.speedevand.inkride.core.domain.tracking.GpsQuality
import com.speedevand.inkride.core.domain.tracking.RideMetrics
import com.speedevand.inkride.core.domain.tracking.WeatherTrend
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.DISTANCE_ZERO
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.KM_TO_MI_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.M_TO_FT_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.TIME_ZERO
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import java.util.Locale

data class RideMetricsUi(
    val currentSpeedKmh: String = "0.0",
    val averageSpeedKmh: String = "0.0",
    val maxSpeedKmh: String = "0.0",
    val distanceKm: String = DISTANCE_ZERO,
    val movingTime: String = TIME_ZERO,
    val elapsedTime: String = TIME_ZERO,
    val altitudeM: String = "--",
    val elevationGainM: String = "0",
    val gradePercent: String = "0.0",
    val caloriesKcal: String = "0",
    val powerWatts: String = "0",
    val gpsAccuracyM: String = "--",
    val bearingDegrees: Float? = null,
    // Null when no BLE sensor of that kind is connected.
    val heartRateBpm: String? = null,
    val cadenceRpm: String? = null,
    // Raw weather trend; the composable maps it to a localized symbol + label.
    val weatherTrend: WeatherTrend = WeatherTrend.UNKNOWN,
    val speedUnit: String = "km/h",
    val distanceUnit: String = "km",
    val altitudeUnit: String = "m"
)

fun RideMetrics.toRideMetricsUi(units: MeasurementUnits = MeasurementUnits.METRIC): RideMetricsUi {
    val speedFactor = if (units == MeasurementUnits.IMPERIAL) KM_TO_MI_FACTOR else 1.0
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) KM_TO_MI_FACTOR else 1.0
    val altitudeFactor = if (units == MeasurementUnits.IMPERIAL) M_TO_FT_FACTOR else 1.0
    
    return RideMetricsUi(
        currentSpeedKmh = (currentSpeedKmh * speedFactor).format(1),
        averageSpeedKmh = (averageSpeedKmh * speedFactor).format(1),
        maxSpeedKmh = (maxSpeedKmh * speedFactor).format(1),
        distanceKm = (distanceKm * distanceFactor).format(2),
        movingTime = movingTimeSeconds.toClockString(),
        elapsedTime = elapsedTimeSeconds.toClockString(),
        altitudeM = altitudeM?.let { (it * altitudeFactor).format(0) } ?: "--",
        elevationGainM = (elevationGainM * altitudeFactor).format(0),
        gradePercent = gradePercent.format(1),
        caloriesKcal = caloriesKcal.format(0),
        powerWatts = powerWatts.toString(),
        gpsAccuracyM = formatGpsQuality(gpsQuality, gpsAccuracyM?.toDouble(), altitudeFactor),
        bearingDegrees = bearingDegrees,
        heartRateBpm = heartRateBpm?.toString(),
        cadenceRpm = cadenceRpm?.toString(),
        weatherTrend = weatherTrend,
        speedUnit = if (units == MeasurementUnits.IMPERIAL) "mph" else "km/h",
        distanceUnit = if (units == MeasurementUnits.IMPERIAL) "mi" else "km",
        altitudeUnit = if (units == MeasurementUnits.IMPERIAL) "ft" else "m"
    )
}

private fun Double.format(decimals: Int): String {
    val format = "%1$.${decimals}f"
    return String.format(Locale.US, format, this)
}

private fun formatGpsQuality(quality: GpsQuality, accuracyM: Double?, factor: Double): String {
    val label = when (quality) {
        GpsQuality.GOOD -> "Good"
        GpsQuality.FAIR -> "Fair"
        GpsQuality.POOR -> "Poor"
    }
    val accStr = accuracyM?.let { "${(it * factor).format(1)} m" } ?: "--"
    return "$label ($accStr)"
}
