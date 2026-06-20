package com.speedevand.inkride.core.domain.settings

/**
 * A saved bike configuration. A rider may own several (road / MTB / city), each
 * with its own weight and type; the active one (referenced by
 * [UserSettings.activeBikeProfileId]) supplies the weight/type the metric
 * estimators use.
 */
data class BikeProfile(
    val id: Long = 0L,
    val name: String,
    val weightKg: Double,
    val type: BikeType
)
