package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val weightKg: Int,
    val age: Int,
    val bikeWeightKg: Double,
    val bikeType: String,
    val languageCode: String,
    val units: String,
    val showDistance: Boolean,
    val showMovingTime: Boolean,
    val showAverageSpeed: Boolean,
    val showMaxSpeed: Boolean,
    val showElevationGain: Boolean,
    val showCalories: Boolean,
    val showAltitude: Boolean,
    val showGrade: Boolean,
    val showCompass: Boolean,
    val showPower: Boolean,
    val keepScreenOn: Boolean = true,
    val pairedHrmAddress: String? = null,
    val pairedCadenceAddress: String? = null,
    val maxSpeedAlertKmh: Double? = null,
    val hrZoneMinBpm: Int? = null,
    val hrZoneMaxBpm: Int? = null,
    val activeBikeProfileId: Long? = null,
)
