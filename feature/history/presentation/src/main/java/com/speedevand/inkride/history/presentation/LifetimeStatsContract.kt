package com.speedevand.inkride.history.presentation

import androidx.compose.runtime.Stable
import com.speedevand.inkride.core.CoreConstants.FORMAT_NO_DECIMALS
import com.speedevand.inkride.core.CoreConstants.FORMAT_ONE_DECIMAL
import com.speedevand.inkride.core.CoreConstants.SECONDS_IN_HOUR
import com.speedevand.inkride.core.CoreConstants.SECONDS_IN_MINUTE
import com.speedevand.inkride.core.CoreConstants.UNIT_FT
import com.speedevand.inkride.core.CoreConstants.UNIT_KCAL
import com.speedevand.inkride.core.CoreConstants.UNIT_KM
import com.speedevand.inkride.core.CoreConstants.UNIT_KMH
import com.speedevand.inkride.core.CoreConstants.UNIT_M
import com.speedevand.inkride.core.CoreConstants.UNIT_MI
import com.speedevand.inkride.core.CoreConstants.UNIT_MPH
import com.speedevand.inkride.core.domain.history.LifetimeStats
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import java.util.Locale

@Stable
data class LifetimeStatsState(
    val stats: LifetimeStatsUi = LifetimeStatsUi(),
    val isLoading: Boolean = true
)

data class LifetimeStatsUi(
    val totalRides: String = "0",
    val totalDistance: String = "0.0 km",
    val totalMovingTime: String = "0h 0m",
    val totalElevationGain: String = "0 m",
    val maxSpeed: String = "0.0 km/h",
    val totalCalories: String = "0 kcal"
)

sealed interface LifetimeStatsAction {
    data object OnBackClick : LifetimeStatsAction
}

sealed interface LifetimeStatsEvent {
    data object NavigateBack : LifetimeStatsEvent
}

fun LifetimeStats.toUi(units: MeasurementUnits = MeasurementUnits.METRIC): LifetimeStatsUi {
    val imperial = units == MeasurementUnits.IMPERIAL
    val distanceFactor = if (imperial) 0.621371 else 1.0
    val altitudeFactor = if (imperial) 3.28084 else 1.0
    val distanceUnit = if (imperial) UNIT_MI else UNIT_KM
    val speedUnit = if (imperial) UNIT_MPH else UNIT_KMH
    val altitudeUnit = if (imperial) UNIT_FT else UNIT_M
    return LifetimeStatsUi(
        totalRides = totalRides.toString(),
        totalDistance = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $distanceUnit", totalDistanceKm * distanceFactor),
        totalMovingTime = totalMovingTimeSeconds.toHoursMinutes(),
        totalElevationGain = String.format(Locale.US, "$FORMAT_NO_DECIMALS $altitudeUnit", totalElevationGainM * altitudeFactor),
        maxSpeed = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", maxSpeedKmh * distanceFactor),
        totalCalories = String.format(Locale.US, "$FORMAT_NO_DECIMALS $UNIT_KCAL", totalCaloriesKcal)
    )
}

private fun Long.toHoursMinutes(): String {
    val hours = this / SECONDS_IN_HOUR
    val minutes = (this % SECONDS_IN_HOUR) / SECONDS_IN_MINUTE
    return String.format(Locale.US, "%dh %dm", hours, minutes)
}
