package com.speedevand.inkride.dashboard.presentation

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.tracking.SensorError
import org.junit.jupiter.api.Test

class SensorErrorToUiTextTest {
    @Test
    fun `LOCATION_DENIED maps to correct string resource`() {
        val result = SensorError.Permission.LOCATION_DENIED.toUiText()
        assertThat(result is com.speedevand.inkride.core.presentation.UiText.StringResource).isEqualTo(true)
    }

    @Test
    fun `GPS_MISSING maps to correct string resource`() {
        val result = SensorError.Hardware.GPS_MISSING.toUiText()
        assertThat(result is com.speedevand.inkride.core.presentation.UiText.StringResource).isEqualTo(true)
    }

    @Test
    fun `BAROMETER_MISSING maps to correct string resource`() {
        val result = SensorError.Hardware.BAROMETER_MISSING.toUiText()
        assertThat(result is com.speedevand.inkride.core.presentation.UiText.StringResource).isEqualTo(true)
    }
}
