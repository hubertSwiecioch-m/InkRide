package com.speedevand.inkride.settings.data

import android.database.sqlite.SQLiteFullException
import com.speedevand.inkride.core.database.BikeProfileDao
import com.speedevand.inkride.core.database.UserSettingsDao
import com.speedevand.inkride.core.database.UserSettingsEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.AlertConfig
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomUserSettingsRepository(
    private val dao: UserSettingsDao,
    private val bikeProfileDao: BikeProfileDao,
) : UserSettingsRepository {
    // The active bike profile (when set and still present) supplies the bike
    // weight/type the metric estimators use, so the stored flat columns act only
    // as the fallback when no profile is active. Resolving it here keeps
    // RideTracker and the estimators unaware of profiles.
    override fun observeSettings(): Flow<UserSettings> =
        combine(dao.observe(), bikeProfileDao.observeAll()) { entity, profiles ->
            if (entity != null) {
                val activeProfile = entity.activeBikeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
                UserSettings(
                    weightKg = entity.weightKg,
                    age = entity.age,
                    bikeWeightKg = activeProfile?.weightKg ?: entity.bikeWeightKg,
                    bikeType =
                        activeProfile?.type?.let { runCatching { BikeType.valueOf(it) }.getOrDefault(BikeType.ROAD) }
                            ?: try {
                                BikeType.valueOf(entity.bikeType)
                            } catch (e: Exception) {
                                BikeType.ROAD
                            },
                    languageCode = entity.languageCode,
                    units =
                        try {
                            MeasurementUnits.valueOf(entity.units)
                        } catch (e: Exception) {
                            MeasurementUnits.METRIC
                        },
                    showDistance = entity.showDistance,
                    showMovingTime = entity.showMovingTime,
                    showAverageSpeed = entity.showAverageSpeed,
                    showMaxSpeed = entity.showMaxSpeed,
                    showElevationGain = entity.showElevationGain,
                    showCalories = entity.showCalories,
                    showAltitude = entity.showAltitude,
                    showGrade = entity.showGrade,
                    showCompass = entity.showCompass,
                    showPower = entity.showPower,
                    keepScreenOn = entity.keepScreenOn,
                    pairedHrmAddress = entity.pairedHrmAddress,
                    pairedCadenceAddress = entity.pairedCadenceAddress,
                    alerts =
                        AlertConfig(
                            maxSpeedKmh = entity.maxSpeedAlertKmh,
                            hrZoneMinBpm = entity.hrZoneMinBpm,
                            hrZoneMaxBpm = entity.hrZoneMaxBpm,
                        ),
                    activeBikeProfileId = entity.activeBikeProfileId,
                )
            } else {
                UserSettings(
                    weightKg = 75,
                    age = 30,
                    languageCode = "en",
                    units = MeasurementUnits.METRIC,
                )
            }
        }

    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> =
        try {
            dao.upsert(
                UserSettingsEntity(
                    weightKg = settings.weightKg,
                    age = settings.age,
                    bikeWeightKg = settings.bikeWeightKg,
                    bikeType = settings.bikeType.name,
                    languageCode = settings.languageCode,
                    units = settings.units.name,
                    showDistance = settings.showDistance,
                    showMovingTime = settings.showMovingTime,
                    showAverageSpeed = settings.showAverageSpeed,
                    showMaxSpeed = settings.showMaxSpeed,
                    showElevationGain = settings.showElevationGain,
                    showCalories = settings.showCalories,
                    showAltitude = settings.showAltitude,
                    showGrade = settings.showGrade,
                    showCompass = settings.showCompass,
                    showPower = settings.showPower,
                    keepScreenOn = settings.keepScreenOn,
                    pairedHrmAddress = settings.pairedHrmAddress,
                    pairedCadenceAddress = settings.pairedCadenceAddress,
                    maxSpeedAlertKmh = settings.alerts.maxSpeedKmh,
                    hrZoneMinBpm = settings.alerts.hrZoneMinBpm,
                    hrZoneMaxBpm = settings.alerts.hrZoneMaxBpm,
                    activeBikeProfileId = settings.activeBikeProfileId,
                ),
            )
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
}
