package com.speedevand.inkride.core.database

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * v4 → v5: adds the `ride_lap` table (lap tracking) and two nullable paired-BLE
 * address columns to `user_settings`. Written as an additive migration so an
 * upgrading user keeps their entire ride history; the destructive fallback only
 * covers gaps from much older schema versions.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ride_lap` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`rideId` INTEGER NOT NULL, " +
                "`lapNumber` INTEGER NOT NULL, " +
                "`distanceKm` REAL NOT NULL, " +
                "`movingTimeSeconds` INTEGER NOT NULL, " +
                "`averageSpeedKmh` REAL NOT NULL, " +
                "`elevationGainM` REAL NOT NULL, " +
                "FOREIGN KEY(`rideId`) REFERENCES `ride_history`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ride_lap_rideId` ON `ride_lap` (`rideId`)")
        db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `pairedHrmAddress` TEXT")
        db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `pairedCadenceAddress` TEXT")
    }
}

/**
 * v5 → v6: Priority-3 additions — speed/HR alert thresholds and the active-bike
 * column on `user_settings`, plus the new `bike_profile` table. Existing flat
 * bike weight/type is seeded into a "Default" profile and made active, so an
 * upgrading rider keeps their current bike with no setup.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `maxSpeedAlertKmh` REAL")
        db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `hrZoneMinBpm` INTEGER")
        db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `hrZoneMaxBpm` INTEGER")
        db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `activeBikeProfileId` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bike_profile` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`weightKg` REAL NOT NULL, " +
                "`type` TEXT NOT NULL )"
        )
        // Seed a default profile from the existing flat bike settings, if any.
        db.execSQL(
            "INSERT INTO `bike_profile` (`name`, `weightKg`, `type`) " +
                "SELECT 'Default', `bikeWeightKg`, `bikeType` FROM `user_settings` WHERE `id` = 1"
        )
        db.execSQL(
            "UPDATE `user_settings` SET `activeBikeProfileId` = " +
                "(SELECT `id` FROM `bike_profile` ORDER BY `id` ASC LIMIT 1) WHERE `id` = 1"
        )
    }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "inkride.db"
        )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()
    }

    single { get<AppDatabase>().rideHistoryDao() }
    single { get<AppDatabase>().userSettingsDao() }
    single { get<AppDatabase>().rideTrackPointDao() }
    single { get<AppDatabase>().rideLapDao() }
    single { get<AppDatabase>().bikeProfileDao() }
}
