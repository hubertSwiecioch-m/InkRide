package com.speedevand.inkride.core.domain.settings

enum class MeasurementUnits {
    METRIC, IMPERIAL
}

enum class BikeType {
    ROAD, MTB, CITY
}

data class UserSettings(
    val weightKg: Int,
    val age: Int,
    val bikeWeightKg: Double = 10.0,
    val bikeType: BikeType = BikeType.ROAD,
    val languageCode: String = "en",
    val units: MeasurementUnits = MeasurementUnits.METRIC,
    val showDistance: Boolean = true,
    val showMovingTime: Boolean = true,
    val showAverageSpeed: Boolean = true,
    val showMaxSpeed: Boolean = true,
    val showElevationGain: Boolean = true,
    val showCalories: Boolean = true,
    val showAltitude: Boolean = true,
    val showGrade: Boolean = true,
    val showCompass: Boolean = true,
    val showPower: Boolean = true,
    val keepScreenOn: Boolean = true,
    // MAC addresses of paired BLE sensors; null when none is paired.
    val pairedHrmAddress: String? = null,
    val pairedCadenceAddress: String? = null,
    // Speed / heart-rate alert thresholds (each field null = that alert off).
    val alerts: AlertConfig = AlertConfig(),
    // Active bike profile; when set and resolvable, its weight/type drive the
    // metric estimators (see [bikeWeightKg]/[bikeType], which are populated from
    // it). Null falls back to the flat [bikeWeightKg]/[bikeType] defaults.
    val activeBikeProfileId: Long? = null
)
