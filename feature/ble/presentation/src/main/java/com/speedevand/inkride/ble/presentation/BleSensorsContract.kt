package com.speedevand.inkride.ble.presentation

import androidx.compose.runtime.Stable
import com.speedevand.inkride.core.domain.ble.BleDevice
import com.speedevand.inkride.core.domain.ble.BleSensorType
import com.speedevand.inkride.core.presentation.UiText

@Stable
data class BleSensorsState(
    val pairedHrmAddress: String? = null,
    val pairedCadenceAddress: String? = null,
    // Non-null while a scan is running; identifies which sensor kind is scanned.
    val scanningType: BleSensorType? = null,
    val discovered: List<BleDevice> = emptyList()
)

sealed interface BleSensorsAction {
    data class OnScanClick(val type: BleSensorType) : BleSensorsAction
    data object OnStopScanClick : BleSensorsAction
    data class OnDeviceClick(val device: BleDevice) : BleSensorsAction
    data class OnForgetClick(val type: BleSensorType) : BleSensorsAction
    data object OnBackClick : BleSensorsAction
}

sealed interface BleSensorsEvent {
    data object NavigateBack : BleSensorsEvent
    data class ShowError(val message: UiText) : BleSensorsEvent
}
