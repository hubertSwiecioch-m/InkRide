package com.speedevand.inkride.core.domain.ble

import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Error
import kotlinx.coroutines.flow.Flow

/**
 * One-shot BLE discovery used by the pairing UI. Emits devices advertising the
 * service matching [type] until the returned flow is cancelled. [available]
 * reports whether the adapter is on and the app holds scan permission, so the UI
 * can prompt before starting a scan.
 */
interface BleScanner {
    fun available(): EmptyResult<BleScanError>
    fun scan(type: BleSensorType): Flow<BleDevice>
}

enum class BleScanError : Error {
    BLUETOOTH_OFF,
    PERMISSION_DENIED,
    UNSUPPORTED
}
