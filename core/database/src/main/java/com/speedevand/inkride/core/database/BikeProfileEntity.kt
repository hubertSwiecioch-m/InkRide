package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bike_profile")
data class BikeProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val weightKg: Double,
    val type: String
)
