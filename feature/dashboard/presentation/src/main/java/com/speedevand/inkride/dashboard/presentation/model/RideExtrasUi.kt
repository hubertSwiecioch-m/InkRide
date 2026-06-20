package com.speedevand.inkride.dashboard.presentation.model

import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.LapRecord
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RideGoal
import com.speedevand.inkride.core.domain.tracking.RideMetrics
import com.speedevand.inkride.core.domain.tracking.RouteProgress
import com.speedevand.inkride.core.toClockString
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.KM_TO_MI_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.M_TO_FT_FACTOR
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Formatted summary of a single completed lap, shown on the dashboard / detail. */
data class LapSummaryUi(
    val lapNumber: Int,
    val distance: String,
    val time: String,
    val averageSpeed: String,
    val elevationGain: String,
)

/**
 * Progress toward the active ride goal. [remainingValue]/[unitLabel] are
 * pre-formatted; the Composable joins them with a localized "to go" template, or
 * shows a "reached" message when [reached] is true.
 */
data class GoalProgressUi(
    val remainingValue: String,
    val unitLabel: String,
    val reached: Boolean,
)

fun LapRecord.toSummaryUi(units: MeasurementUnits): LapSummaryUi {
    val imperial = units == MeasurementUnits.IMPERIAL
    val distanceFactor = if (imperial) KM_TO_MI_FACTOR else 1.0
    val altitudeFactor = if (imperial) 3.28084 else 1.0
    val distanceUnit = if (imperial) "mi" else "km"
    val speedUnit = if (imperial) "mph" else "km/h"
    val altitudeUnit = if (imperial) "ft" else "m"
    return LapSummaryUi(
        lapNumber = lapNumber,
        distance = "${(distanceKm * distanceFactor).format(2)} $distanceUnit",
        time = movingTimeSeconds.toClockString(),
        averageSpeed = "${(averageSpeedKmh * distanceFactor).format(1)} $speedUnit",
        elevationGain = "${(elevationGainM * altitudeFactor).format(0)} $altitudeUnit",
    )
}

fun goalProgressUi(
    goal: RideGoal,
    metrics: RideMetrics,
    units: MeasurementUnits,
): GoalProgressUi {
    val imperial = units == MeasurementUnits.IMPERIAL
    return when (goal) {
        is RideGoal.Distance -> {
            val factor = if (imperial) KM_TO_MI_FACTOR else 1.0
            val remainingKm = (goal.targetKm - metrics.distanceKm).coerceAtLeast(0.0)
            GoalProgressUi(
                remainingValue = (remainingKm * factor).format(2),
                unitLabel = if (imperial) "mi" else "km",
                reached = metrics.distanceKm >= goal.targetKm,
            )
        }

        is RideGoal.Duration -> {
            val remainingSec = (goal.targetSeconds - metrics.elapsedTimeSeconds).coerceAtLeast(0L)
            val remainingMin = ceil(remainingSec / 60.0).toInt()
            GoalProgressUi(
                remainingValue = remainingMin.toString(),
                unitLabel = "min",
                reached = metrics.elapsedTimeSeconds >= goal.targetSeconds,
            )
        }
    }
}

/**
 * Formatted route-follow readout for the dashboard. [nextTurn] is the distance to
 * the next turn marker (null when none lies ahead); [offRoute] drives a static
 * E-Ink warning showing [offRouteDistance] off the planned line.
 */
data class RouteProgressUi(
    val routeName: String?,
    val nextTurn: String?,
    val nextTurnName: String?,
    val offRoute: Boolean,
    val offRouteDistance: String,
)

fun routeProgressUi(
    route: PlannedRoute,
    progress: RouteProgress?,
    units: MeasurementUnits,
): RouteProgressUi {
    val imperial = units == MeasurementUnits.IMPERIAL
    return RouteProgressUi(
        routeName = route.name,
        nextTurn = progress?.distanceToNextWaypointM?.let { formatRouteDistance(it, imperial) },
        nextTurnName = progress?.nextWaypointName,
        offRoute = progress?.isOffRoute == true,
        offRouteDistance = progress?.distanceToRouteM?.let { formatRouteDistance(it, imperial) }.orEmpty(),
    )
}

/** Short distance: metres/feet under ~1 km, otherwise km/miles to 2 decimals. */
private fun formatRouteDistance(
    meters: Double,
    imperial: Boolean,
): String =
    if (imperial) {
        val feet = meters * M_TO_FT_FACTOR
        if (feet < 1000) "${feet.roundToInt()} ft" else "${(meters * KM_TO_MI_FACTOR / 1000.0).format(2)} mi"
    } else {
        if (meters < 1000) "${meters.roundToInt()} m" else "${(meters / 1000.0).format(2)} km"
    }

private fun Double.format(decimals: Int): String = String.format(Locale.US, "%1$.${decimals}f", this)
