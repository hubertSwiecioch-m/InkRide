package com.speedevand.inkride.settings.presentation

import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.settings.presentation.SettingsConstants.KMH_TO_MPH_FACTOR
import java.util.Locale

data class UserSettingsUi(
    val weightKg: String = "75",
    val age: String = "30",
    val bikeWeightKg: String = "10",
    val bikeType: BikeType = BikeType.ROAD,
    val units: MeasurementUnits = MeasurementUnits.METRIC,
    val showPower: Boolean = true,
    val weightError: UiText? = null,
    val ageError: UiText? = null,
    val bikeWeightError: UiText? = null,
    // Alert thresholds as editable text; blank means the alert is disabled.
    // Speed is shown in the user's current unit (km/h or mph).
    val maxSpeedAlert: String = "",
    val hrMinAlert: String = "",
    val hrMaxAlert: String = ""
)

fun UserSettings.toUserSettingsUi(): UserSettingsUi {
    val weightFactor = if (units == MeasurementUnits.IMPERIAL) 2.20462 else 1.0
    val speedFactor = if (units == MeasurementUnits.IMPERIAL) KMH_TO_MPH_FACTOR else 1.0
    return UserSettingsUi(
        weightKg = String.format(Locale.ROOT, "%.0f", weightKg.toDouble() * weightFactor),
        age = age.toString(),
        bikeWeightKg = String.format(Locale.ROOT, "%.1f", bikeWeightKg * weightFactor),
        bikeType = bikeType,
        units = units,
        showPower = showPower,
        maxSpeedAlert = alerts.maxSpeedKmh?.let { String.format(Locale.ROOT, "%.0f", it * speedFactor) } ?: "",
        hrMinAlert = alerts.hrZoneMinBpm?.toString() ?: "",
        hrMaxAlert = alerts.hrZoneMaxBpm?.toString() ?: ""
    )
}
