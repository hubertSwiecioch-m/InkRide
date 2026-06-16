package com.speedevand.inkride.history.presentation

import androidx.compose.runtime.Stable
import com.speedevand.inkride.core.CoreConstants.DATE_TIME_FORMAT
import com.speedevand.inkride.core.CoreConstants.FORMAT_ONE_DECIMAL
import com.speedevand.inkride.core.CoreConstants.FORMAT_TWO_DECIMALS
import com.speedevand.inkride.core.toClockString
import com.speedevand.inkride.core.CoreConstants.UNIT_KM
import com.speedevand.inkride.core.CoreConstants.UNIT_KMH
import com.speedevand.inkride.core.CoreConstants.UNIT_MI
import com.speedevand.inkride.core.CoreConstants.UNIT_MPH
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── UI Model ──────────────────────────────────────────────────────────────────

data class RideRecordUi(
    val id: Long,
    val formattedDate: String,
    val distanceKm: String,
    val movingTime: String,
    val averageSpeedKmh: String
)

fun RideRecord.toUi(units: MeasurementUnits = MeasurementUnits.METRIC): RideRecordUi {
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0
    val speedFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0

    val distanceUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MI else UNIT_KM
    val speedUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MPH else UNIT_KMH

    val sdf = SimpleDateFormat(DATE_TIME_FORMAT, Locale.getDefault())
    return RideRecordUi(
        id = id,
        formattedDate = sdf.format(Date(startTimestamp)),
        distanceKm = String.format(Locale.US, "$FORMAT_TWO_DECIMALS $distanceUnit", distanceKm * distanceFactor),
        movingTime = movingTimeSeconds.toClockString(),
        averageSpeedKmh = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", averageSpeedKmh * speedFactor)
    )
}

// ── State ─────────────────────────────────────────────────────────────────────

@Stable
data class RideHistoryState(
    val rides: List<RideRecordUi> = emptyList(),
    val isLoading: Boolean = true
)

// ── Action ────────────────────────────────────────────────────────────────────

sealed interface RideHistoryAction {
    data class OnRideClick(val id: Long) : RideHistoryAction
    data class OnDeleteRide(val id: Long) : RideHistoryAction
    data object OnUndoDelete : RideHistoryAction
    data object OnDeleteAll : RideHistoryAction
    data object OnLifetimeStatsClick : RideHistoryAction
}

// ── Event ─────────────────────────────────────────────────────────────────────

sealed interface RideHistoryEvent {
    data class NavigateToDetail(val id: Long) : RideHistoryEvent
    data object NavigateToLifetimeStats : RideHistoryEvent
    data class ShowError(val message: UiText) : RideHistoryEvent
    data object ShowUndoSnackbar : RideHistoryEvent
}
