package com.speedevand.inkride.settings.presentation

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import org.junit.jupiter.api.Test

class UserSettingsUiTest {

    @Test
    fun `toUserSettingsUi converts metric correctly`() {
        val settings = UserSettings(weightKg = 75, age = 30, bikeWeightKg = 10.0, units = MeasurementUnits.METRIC)
        val ui = settings.toUserSettingsUi()
        assertThat(ui.weightKg).isEqualTo("75")
        assertThat(ui.age).isEqualTo("30")
        assertThat(ui.bikeWeightKg).isEqualTo("10.0")
        assertThat(ui.units).isEqualTo(MeasurementUnits.METRIC)
    }

    @Test
    fun `toUserSettingsUi converts imperial correctly`() {
        val settings = UserSettings(weightKg = 75, age = 30, bikeWeightKg = 10.0, units = MeasurementUnits.IMPERIAL)
        val ui = settings.toUserSettingsUi()
        // 75 * 2.20462 = 165.3465, formatted as "165"
        assertThat(ui.weightKg).isEqualTo("165")
        // 10.0 * 2.20462 = 22.0462, formatted as "22.0"
        assertThat(ui.bikeWeightKg).isEqualTo("22.0")
    }

    @Test
    fun `metric weight not multiplied`() {
        val settings = UserSettings(weightKg = 80, age = 30, units = MeasurementUnits.METRIC)
        val ui = settings.toUserSettingsUi()
        assertThat(ui.weightKg).isEqualTo("80")
    }

    @Test
    fun `imperial weight multiplied by factor`() {
        val settings = UserSettings(weightKg = 100, age = 30, units = MeasurementUnits.IMPERIAL)
        val ui = settings.toUserSettingsUi()
        // 100 * 2.20462 = 220.462 → "220"
        assertThat(ui.weightKg).isEqualTo("220")
    }

    @Test
    fun `bike type preserved`() {
        val settings = UserSettings(weightKg = 75, age = 30, bikeType = BikeType.MTB)
        val ui = settings.toUserSettingsUi()
        assertThat(ui.bikeType).isEqualTo(BikeType.MTB)
    }

    @Test
    fun `showPower flag preserved`() {
        val settings = UserSettings(weightKg = 75, age = 30, showPower = false)
        val ui = settings.toUserSettingsUi()
        assertThat(ui.showPower).isEqualTo(false)
    }
}
