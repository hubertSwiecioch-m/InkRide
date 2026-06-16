package com.speedevand.inkride.settings.presentation

object SettingsConstants {
    const val WEIGHT_FACTOR_LBS = 2.20462
    const val WEIGHT_MIN_KG = 30f
    const val WEIGHT_MAX_KG = 200f
    const val WEIGHT_MIN_LBS = 66f
    const val WEIGHT_MAX_LBS = 441f

    const val AGE_MIN = 10f
    const val AGE_MAX = 100f

    const val BIKE_WEIGHT_MIN_KG = 5f
    const val BIKE_WEIGHT_MAX_KG = 50f
    const val BIKE_WEIGHT_MIN_LBS = 11f
    const val BIKE_WEIGHT_MAX_LBS = 110f

    // Speed shown in km/h internally; convert for imperial display/entry.
    const val KMH_TO_MPH_FACTOR = 0.621371

    // Alert thresholds — bounds and defaults in internal units (km/h / bpm).
    const val ALERT_SPEED_MIN_KMH = 10.0
    const val ALERT_SPEED_MAX_KMH = 160.0
    const val ALERT_SPEED_DEFAULT_KMH = 30.0
    const val ALERT_SPEED_STEP = 5.0

    const val ALERT_HR_MIN_BPM = 40
    const val ALERT_HR_MAX_BPM = 220
    const val ALERT_HR_DEFAULT_MIN_BPM = 60
    const val ALERT_HR_DEFAULT_MAX_BPM = 180
    const val ALERT_HR_STEP = 5.0
}
