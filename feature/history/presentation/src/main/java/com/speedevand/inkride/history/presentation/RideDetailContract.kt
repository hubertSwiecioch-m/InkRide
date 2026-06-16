package com.speedevand.inkride.history.presentation

import com.speedevand.inkride.core.CoreConstants.DATE_TIME_FORMAT
import com.speedevand.inkride.core.CoreConstants.FORMAT_NO_DECIMALS
import com.speedevand.inkride.core.CoreConstants.FORMAT_ONE_DECIMAL
import com.speedevand.inkride.core.CoreConstants.FORMAT_TWO_DECIMALS
import com.speedevand.inkride.core.CoreConstants.UNIT_KCAL
import com.speedevand.inkride.core.toClockString
import com.speedevand.inkride.core.CoreConstants.UNIT_FT
import com.speedevand.inkride.core.CoreConstants.UNIT_KM
import com.speedevand.inkride.core.CoreConstants.UNIT_KMH
import com.speedevand.inkride.core.CoreConstants.UNIT_MI
import com.speedevand.inkride.core.CoreConstants.UNIT_MPH
import com.speedevand.inkride.core.CoreConstants.UNIT_M
import com.speedevand.inkride.core.CoreConstants.UNIT_W
import android.net.Uri
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.LapRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RideDetailState(
    val ride: RideDetailUi? = null,
    val laps: List<RideLapUi> = emptyList(),
    val trackPoints: List<TrackPointUi> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * A single recorded GPS position for plotting the route polyline. Kept free of
 * any OsmDroid type so the ViewModel stays unit-testable; the map composable
 * maps these to `org.osmdroid.util.GeoPoint`.
 */
data class TrackPointUi(
    val lat: Double,
    val lng: Double
)

data class RideLapUi(
    val lapNumber: String,
    val distance: String,
    val time: String,
    val averageSpeed: String
)

fun LapRecord.toLapUi(units: MeasurementUnits = MeasurementUnits.METRIC): RideLapUi {
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0
    val distanceUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MI else UNIT_KM
    val speedUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MPH else UNIT_KMH
    return RideLapUi(
        lapNumber = lapNumber.toString(),
        distance = String.format(Locale.US, "$FORMAT_TWO_DECIMALS $distanceUnit", distanceKm * distanceFactor),
        time = movingTimeSeconds.toClockString(),
        averageSpeed = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", averageSpeedKmh * distanceFactor)
    )
}

data class RideDetailUi(
    val id: Long,
    val formattedDate: String,
    val formattedEndDate: String,
    val distanceKm: String,
    val movingTime: String,
    val elapsedTime: String,
    val averageSpeedKmh: String,
    val maxSpeedKmh: String,
    val elevationGainM: String,
    val caloriesKcal: String,
    val averagePowerWatts: String
)

fun RideRecord.toDetailUi(units: MeasurementUnits = MeasurementUnits.METRIC): RideDetailUi {
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0
    val speedFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0
    val altitudeFactor = if (units == MeasurementUnits.IMPERIAL) 3.28084 else 1.0

    val distanceUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MI else UNIT_KM
    val speedUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MPH else UNIT_KMH
    val altitudeUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_FT else UNIT_M

    val sdf = SimpleDateFormat(DATE_TIME_FORMAT, Locale.getDefault())
    return RideDetailUi(
        id = id,
        formattedDate = sdf.format(Date(startTimestamp)),
        formattedEndDate = sdf.format(Date(endTimestamp)),
        distanceKm = String.format(Locale.US, "$FORMAT_TWO_DECIMALS $distanceUnit", distanceKm * distanceFactor),
        movingTime = movingTimeSeconds.toClockString(),
        elapsedTime = elapsedTimeSeconds.toClockString(),
        averageSpeedKmh = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", averageSpeedKmh * speedFactor),
        maxSpeedKmh = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", maxSpeedKmh * speedFactor),
        elevationGainM = String.format(Locale.US, "$FORMAT_NO_DECIMALS $altitudeUnit", elevationGainM * altitudeFactor),
        caloriesKcal = String.format(Locale.US, "$FORMAT_NO_DECIMALS $UNIT_KCAL", caloriesKcal),
        averagePowerWatts = String.format(Locale.US, "$FORMAT_NO_DECIMALS $UNIT_W", averagePowerWatts.toDouble())
    )
}

sealed interface RideDetailAction {
    data object OnDeleteClick : RideDetailAction
    data object OnBackClick : RideDetailAction
    data object OnExportGpxClick : RideDetailAction
}

sealed interface RideDetailEvent {
    data object NavigateBack : RideDetailEvent
    data class ShowError(val message: UiText) : RideDetailEvent
    data class ShareGpx(val uri: Uri) : RideDetailEvent
}
