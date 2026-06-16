package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error

sealed interface SensorError : Error {
    enum class Permission : SensorError {
        LOCATION_DENIED
    }
    enum class Hardware : SensorError {
        BAROMETER_MISSING,
        GPS_MISSING
    }
}
