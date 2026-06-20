package com.speedevand.inkride.dashboard.presentation

import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.domain.tracking.SensorError

fun SensorError.toUiText(): UiText {
    return when (this) {
        SensorError.Permission.LOCATION_DENIED -> UiText.StringResource(R.string.error_location_permission_denied)
        SensorError.Hardware.GPS_MISSING -> UiText.StringResource(R.string.error_gps_missing)
        SensorError.Hardware.BAROMETER_MISSING -> UiText.StringResource(R.string.error_barometer_missing)
    }
}
