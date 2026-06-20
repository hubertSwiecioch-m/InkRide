package com.speedevand.inkride.core.domain.ble

/** The kinds of BLE sensors InkRide can pair with. */
enum class BleSensorType {
    /** Heart Rate Monitor — GATT service 0x180D. */
    HEART_RATE,

    /** Cycling Speed and Cadence — GATT service 0x1816. */
    CADENCE,
}
