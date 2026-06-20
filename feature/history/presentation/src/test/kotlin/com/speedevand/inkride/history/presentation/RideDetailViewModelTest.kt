package com.speedevand.inkride.history.presentation

import android.net.Uri
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val rideRepo = FakeRideHistoryRepository()
    private val lapRepo = FakeRideLapRepository()
    private val trackPointRepo = FakeRideTrackPointRepository()
    private val settingsRepo = FakeUserSettingsRepository()
    private val gpxExporter = FakeGpxExporter()

    private val sampleRide =
        RideRecord(
            id = 1L,
            startTimestamp = 0L,
            endTimestamp = 1000L,
            distanceKm = 10.0,
            movingTimeSeconds = 600L,
            elapsedTimeSeconds = 1200L,
            averageSpeedKmh = 20.0,
            maxSpeedKmh = 30.0,
            elevationGainM = 50.0,
            caloriesKcal = 200.0,
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load by ID success populates state`() =
        runTest {
            rideRepo.setRide(sampleRide)
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.ride).isNotNull()
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        }

    @Test
    fun `load by ID failure shows error`() =
        runTest {
            rideRepo.getByIdResult = Result.Error(DataError.Local.NOT_FOUND)
            val viewModel = RideDetailViewModel(99L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
            assertThat(viewModel.state.value.ride).isEqualTo(null)
        }

    @Test
    fun `back click navigates back`() =
        runTest {
            rideRepo.setRide(sampleRide)
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            viewModel.events.test {
                viewModel.onAction(RideDetailAction.OnBackClick)
                val event = awaitItem()
                assertThat(event).isEqualTo(RideDetailEvent.NavigateBack)
            }
        }

    @Test
    fun `delete click deletes and navigates back`() =
        runTest {
            rideRepo.setRide(sampleRide)
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            viewModel.events.test {
                viewModel.onAction(RideDetailAction.OnDeleteClick)
                val event = awaitItem()
                assertThat(event).isEqualTo(RideDetailEvent.NavigateBack)
            }
        }

    @Test
    fun `export with no track shows error`() =
        runTest {
            rideRepo.setRide(sampleRide)
            gpxExporter.result = Result.Error(GpxExportError.NO_TRACK)
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            viewModel.events.test {
                viewModel.onAction(RideDetailAction.OnExportGpxClick)
                val event = awaitItem()
                assertThat(event).isInstanceOf<RideDetailEvent.ShowError>()
            }
        }

    @Test
    fun `track points populate route state`() =
        runTest {
            rideRepo.setRide(sampleRide)
            trackPointRepo.points =
                listOf(
                    RideTrackPoint(timestampMs = 0L, latitude = 52.1, longitude = 21.0),
                    RideTrackPoint(timestampMs = 1000L, latitude = 52.2, longitude = 21.1),
                )
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.trackPoints).isEqualTo(
                listOf(TrackPointUi(52.1, 21.0), TrackPointUi(52.2, 21.1)),
            )
        }

    class FakeRideHistoryRepository : RideHistoryRepository {
        private var ride: RideRecord? = null
        var getByIdResult: Result<RideRecord, DataError.Local> = Result.Error(DataError.Local.NOT_FOUND)

        fun setRide(ride: RideRecord) {
            this.ride = ride
            getByIdResult = Result.Success(ride)
        }

        override fun observeAll(): Flow<List<RideRecord>> = flowOf(emptyList())

        override suspend fun getById(id: Long) = getByIdResult

        override suspend fun save(ride: RideRecord) = Result.Success(ride.id)

        override suspend fun deleteById(id: Long) = Result.Success(Unit)

        override suspend fun deleteAll() = Result.Success(Unit)
    }

    class FakeUserSettingsRepository : UserSettingsRepository {
        override fun observeSettings(): Flow<UserSettings> = flowOf(UserSettings(weightKg = 75, age = 30))

        override suspend fun save(settings: UserSettings) = Result.Success(Unit)
    }

    class FakeRideTrackPointRepository : RideTrackPointRepository {
        var points: List<RideTrackPoint> = emptyList()

        override suspend fun savePoints(
            rideId: Long,
            points: List<RideTrackPoint>,
        ): EmptyResult<DataError.Local> = Result.Success(Unit)

        override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> = Result.Success(points)
    }

    class FakeGpxExporter : GpxExporter {
        var result: Result<Uri, GpxExportError> = Result.Error(GpxExportError.NO_TRACK)

        override suspend fun export(rideId: Long): Result<Uri, GpxExportError> = result
    }

    class FakeRideLapRepository : com.speedevand.inkride.core.domain.history.RideLapRepository {
        override suspend fun saveLaps(
            rideId: Long,
            laps: List<com.speedevand.inkride.core.domain.tracking.LapRecord>,
        ) = Result.Success(Unit)

        override suspend fun getLaps(rideId: Long) =
            Result.Success(
                emptyList<com.speedevand.inkride.core.domain.tracking.LapRecord>(),
            )
    }
}
