package com.speedevand.inkride.core.presentation

import com.speedevand.inkride.core.domain.DataError

fun DataError.toUiText(): UiText {
    return when (this) {
        DataError.Network.NO_INTERNET -> UiText.StringResource(R.string.error_no_internet)
        DataError.Network.SERVER_ERROR -> UiText.StringResource(R.string.error_server)
        DataError.Network.UNAUTHORIZED -> UiText.StringResource(R.string.error_unauthorized)
        DataError.Local.DISK_FULL -> UiText.StringResource(R.string.error_disk_full)
        else -> UiText.StringResource(R.string.error_unknown)
    }
}
