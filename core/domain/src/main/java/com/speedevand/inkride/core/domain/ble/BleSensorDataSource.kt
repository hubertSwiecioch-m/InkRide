package com.speedevand.inkride.core.domain.ble

import kotlinx.coroutines.flow.Flow

/**
 * Live BLE sensor stream consumed by `RideTracker` during a ride. The Android
 * implementation maintains GATT connections to the paired HRM/cadence sensors
 * and folds their notifications into a single [BleSample] flow.
 *
 * [connect] is idempotent: calling it again with the same addresses is a no-op,
 * so it can be driven from a settings flow that re-emits on every change.
 */
interface BleSensorDataSource {
    fun observeSamples(): Flow<BleSample>

    fun connect(
        hrmAddress: String?,
        cadenceAddress: String?,
    )

    fun disconnect()
}
