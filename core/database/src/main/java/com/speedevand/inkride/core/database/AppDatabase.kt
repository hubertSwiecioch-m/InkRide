package com.speedevand.inkride.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserSettingsEntity::class,
        RideHistoryEntity::class,
        RideTrackPointEntity::class,
        RideLapEntity::class,
        BikeProfileEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userSettingsDao(): UserSettingsDao

    abstract fun rideHistoryDao(): RideHistoryDao

    abstract fun rideTrackPointDao(): RideTrackPointDao

    abstract fun rideLapDao(): RideLapDao

    abstract fun bikeProfileDao(): BikeProfileDao
}
