package com.speedevand.inkride.core.domain.ble

/** A BLE peripheral discovered during a scan. */
data class BleDevice(
    val address: String,
    val name: String?,
    val type: BleSensorType
)
