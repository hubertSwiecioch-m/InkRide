package com.speedevand.inkride.dashboard.presentation

import com.speedevand.inkride.core.domain.tracking.SensorError
import com.speedevand.inkride.core.presentation.UiText

fun SensorError.toUiText(): UiText =
    when (this) {
        SensorError.Permission.LOCATION_DENIED -> UiText.StringResource(R.string.error_location_permission_denied)
        SensorError.Hardware.GPS_MISSING -> UiText.StringResource(R.string.error_gps_missing)
        SensorError.Hardware.BAROMETER_MISSING -> UiText.StringResource(R.string.error_barometer_missing)
    }
