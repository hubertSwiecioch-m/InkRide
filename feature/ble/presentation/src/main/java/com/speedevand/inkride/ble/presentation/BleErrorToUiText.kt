package com.speedevand.inkride.ble.presentation

import com.speedevand.inkride.core.domain.ble.BleScanError
import com.speedevand.inkride.core.presentation.UiText

fun BleScanError.toUiText(): UiText =
    UiText.StringResource(
        when (this) {
            BleScanError.BLUETOOTH_OFF -> R.string.ble_error_bluetooth_off
            BleScanError.PERMISSION_DENIED -> R.string.ble_error_permission_denied
            BleScanError.UNSUPPORTED -> R.string.ble_error_unsupported
        },
    )
