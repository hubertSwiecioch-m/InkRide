package com.speedevand.inkride.settings.data

import android.database.sqlite.SQLiteFullException
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.database.BikeProfileDao
import com.speedevand.inkride.core.database.BikeProfileEntity
import com.speedevand.inkride.core.database.UserSettingsDao
import com.speedevand.inkride.core.database.UserSettingsEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomUserSettingsRepositoryTest {

    private val dao = FakeUserSettingsDao()
    private val bikeProfileDao = FakeBikeProfileDao()
    private val repository = RoomUserSettingsRepository(dao, bikeProfileDao)

    @Test
    fun `observeSettings resolves active bike profile weight and type`() = runTest {
        bikeProfileDao.setProfiles(
            listOf(BikeProfileEntity(id = 7, name = "MTB", weightKg = 14.0, type = "MTB"))
        )
        dao.setEntity(
            UserSettingsEntity(
                id = 1, weightKg = 80, age = 25, bikeWeightKg = 12.0,
                bikeType = "ROAD", languageCode = "en", units = "METRIC",
                showDistance = true, showMovingTime = true, showAverageSpeed = true,
                showMaxSpeed = true, showElevationGain = true, showCalories = true,
                showAltitude = true, showGrade = true, showCompass = true, showPower = true,
                activeBikeProfileId = 7
            )
        )

        val settings = repository.observeSettings().first()
        assertThat(settings.bikeWeightKg).isEqualTo(14.0)
        assertThat(settings.bikeType).isEqualTo(com.speedevand.inkride.core.domain.settings.BikeType.MTB)
    }

    @Test
    fun `observeSettings maps entity to domain model`() = runTest {
        dao.setEntity(
            UserSettingsEntity(
                id = 1, weightKg = 80, age = 25, bikeWeightKg = 12.0,
                bikeType = "ROAD", languageCode = "en", units = "METRIC",
                showDistance = true, showMovingTime = true, showAverageSpeed = true,
                showMaxSpeed = true, showElevationGain = true, showCalories = true,
                showAltitude = true, showGrade = true, showCompass = true, showPower = true
            )
        )

        val settings = repository.observeSettings().first()
        assertThat(settings.weightKg).isEqualTo(80)
        assertThat(settings.age).isEqualTo(25)
        assertThat(settings.bikeWeightKg).isEqualTo(12.0)
    }

    @Test
    fun `observeSettings returns defaults when entity is null`() = runTest {
        dao.setEntity(null)

        val settings = repository.observeSettings().first()
        assertThat(settings.weightKg).isEqualTo(75)
        assertThat(settings.age).isEqualTo(30)
        assertThat(settings.units).isEqualTo(MeasurementUnits.METRIC)
    }

    @Test
    fun `save success returns Success result`() = runTest {
        val settings = UserSettings(weightKg = 80, age = 25)
        val result = repository.save(settings)
        assertThat(result).isInstanceOf<Result.Success<Unit>>()
    }

    @Test
    fun `save DISK_FULL returns Error`() = runTest {
        dao.upsertException = SQLiteFullException()
        val result = repository.save(UserSettings(weightKg = 80, age = 25))
        assertThat(result).isInstanceOf<Result.Error<DataError.Local>>()
        assertThat((result as Result.Error).error).isEqualTo(DataError.Local.DISK_FULL)
    }

    @Test
    fun `save unknown exception returns UNKNOWN error`() = runTest {
        dao.upsertException = RuntimeException("test")
        val result = repository.save(UserSettings(weightKg = 80, age = 25))
        assertThat((result as Result.Error).error).isEqualTo(DataError.Local.UNKNOWN)
    }

    class FakeUserSettingsDao : UserSettingsDao {
        private val _flow = MutableStateFlow<UserSettingsEntity?>(null)
        var lastUpsert: UserSettingsEntity? = null
        var upsertException: Exception? = null

        override fun observe(): Flow<UserSettingsEntity?> = _flow

        override suspend fun upsert(settings: UserSettingsEntity) {
            upsertException?.let { throw it }
            lastUpsert = settings
            _flow.value = settings
        }

        fun setEntity(entity: UserSettingsEntity?) {
            _flow.value = entity
        }
    }

    class FakeBikeProfileDao : BikeProfileDao {
        private val _flow = MutableStateFlow<List<BikeProfileEntity>>(emptyList())

        override fun observeAll(): Flow<List<BikeProfileEntity>> = _flow
        override suspend fun upsert(profile: BikeProfileEntity): Long = profile.id
        override suspend fun deleteById(id: Long) = Unit

        fun setProfiles(profiles: List<BikeProfileEntity>) {
            _flow.value = profiles
        }
    }
}
