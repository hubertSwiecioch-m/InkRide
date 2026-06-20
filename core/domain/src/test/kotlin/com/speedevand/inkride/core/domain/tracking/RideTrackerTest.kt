package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.settings.AlertConfig
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideTrackerTest {

    private val settings = UserSettings(weightKg = 75, age = 32)

    @Test
    fun `start sets status to tracking and starts the sensor`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor)

        tracker.start()

        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
        assertThat(sensor.started).isTrue()
    }

    @Test
    fun `start failure surfaces an error and stays idle`() = runTest {
        val sensor = FakeSensorDataSource().apply {
            startResult = Result.Error(SensorError.Permission.LOCATION_DENIED)
        }
        val tracker = newTracker(testScheduler, sensor)
        val errors = mutableListOf<SensorError>()
        // Eager (unconfined) subscriber so it's attached before start() emits.
        val collectorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        collectorScope.launch { tracker.errors.collect { errors.add(it) } }

        tracker.start()

        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.IDLE)
        assertThat(errors).hasSize(1)
        assertThat(errors.first()).isEqualTo(SensorError.Permission.LOCATION_DENIED)
        collectorScope.cancel()
    }

    @Test
    fun `processed samples update live metrics`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor)

        tracker.start()
        sensor.samples.emit(sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 5.0f))

        // 10 m/s = 36 km/h
        assertThat(tracker.state.value.metrics.currentSpeedKmh).isEqualTo(36.0)
    }

    @Test
    fun `pause then resume toggles status without ending the session`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor)

        tracker.start()
        tracker.pause()
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.PAUSED)

        tracker.resume()
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
    }

    @Test
    fun `auto-pause engages after sustained low speed and resumes on movement`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor, autoPauseDelayMs = 2_000L)

        tracker.start()
        // Moving: well above the resume threshold.
        sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)

        // Stopped: low speed must persist beyond the delay before auto-pause.
        sensor.samples.emit(sampleAt(2000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
        sensor.samples.emit(sampleAt(4500L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.AUTO_PAUSED)

        // Moving again: auto-resume.
        sensor.samples.emit(sampleAt(5500L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
    }

    @Test
    fun `manual pause is not auto-resumed by movement`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor)

        tracker.start()
        tracker.pause()
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.PAUSED)

        // Even moving fast, a manual pause must stick.
        sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.PAUSED)
    }

    @Test
    fun `stop saves a ride that covered enough distance and resets`() = runTest {
        val sensor = FakeSensorDataSource()
        val history = FakeHistoryRepository()
        val tracker = newTracker(testScheduler, sensor, history)

        tracker.start()
        // ~11 m over 1 s at a reliable accuracy → distance > 10 m save floor.
        sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        assertThat(tracker.state.value.metrics.distanceKm).isGreaterThan(0.01)

        tracker.stop()

        assertThat(history.saved).hasSize(1)
        assertThat(sensor.stopped).isTrue()
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.IDLE)
        assertThat(tracker.state.value.metrics.distanceKm).isEqualTo(0.0)
    }

    @Test
    fun `stop does not save a ride below the distance floor`() = runTest {
        val sensor = FakeSensorDataSource()
        val history = FakeHistoryRepository()
        val tracker = newTracker(testScheduler, sensor, history)

        tracker.start()
        // Stationary samples → no distance accumulated.
        sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))

        tracker.stop()

        assertThat(history.saved).isEmpty()
        assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.IDLE)
    }

    @Test
    fun `recordLap captures the segment since the previous lap`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor)

        tracker.start()
        sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        val distanceAfterFirst = tracker.state.value.metrics.distanceKm

        tracker.recordLap()
        assertThat(tracker.state.value.laps).hasSize(1)
        assertThat(tracker.state.value.laps.first().lapNumber).isEqualTo(1)
        assertThat(tracker.state.value.laps.first().distanceKm).isEqualTo(distanceAfterFirst)

        sensor.samples.emit(sampleAt(2000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        tracker.recordLap()
        assertThat(tracker.state.value.laps).hasSize(2)
        // Second lap is the delta, so its distance is below the running total.
        assertThat(tracker.state.value.laps[1].distanceKm).isGreaterThan(0.0)
    }

    @Test
    fun `setGoal and clearGoal update the active goal`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor)

        tracker.start()
        tracker.setGoal(RideGoal.Distance(targetKm = 20.0))
        assertThat(tracker.state.value.activeGoal).isEqualTo(RideGoal.Distance(20.0))

        tracker.clearGoal()
        assertThat(tracker.state.value.activeGoal).isNull()
    }

    @Test
    fun `stop clears laps and goal`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(testScheduler, sensor)

        tracker.start()
        tracker.setGoal(RideGoal.Duration(targetSeconds = 600L))
        sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        tracker.recordLap()

        tracker.stop()

        assertThat(tracker.state.value.laps).isEmpty()
        assertThat(tracker.state.value.activeGoal).isNull()
    }

    @Test
    fun `a BLE sample updates live metrics without a GPS fix`() = runTest {
        val sensor = FakeSensorDataSource()
        val ble = FakeBleSensorDataSource()
        val tracker = newTracker(testScheduler, sensor, ble = ble)

        tracker.start()
        // No GPS sample emitted at all — only a heart-rate notification.
        ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88))

        assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)
        assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(88)
    }

    @Test
    fun `over-speed alert fires once when speed crosses the threshold`() = runTest {
        val sensor = FakeSensorDataSource()
        val tracker = newTracker(
            testScheduler,
            sensor,
            settings = settings.copy(alerts = AlertConfig(maxSpeedKmh = 30.0))
        )
        val alerts = mutableListOf<RideAlert>()
        val collectorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        collectorScope.launch { tracker.alerts.collect { alerts.add(it) } }

        tracker.start()
        // 10 m/s = 36 km/h, above the 30 km/h limit.
        sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
        // Still over the limit — must not re-fire (edge-triggered).
        sensor.samples.emit(sampleAt(2000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

        assertThat(alerts).hasSize(1)
        assertThat(alerts.first()).isInstanceOf(RideAlert.OverSpeed::class)
        collectorScope.cancel()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun newTracker(
        scheduler: TestCoroutineScheduler,
        sensor: FakeSensorDataSource,
        history: FakeHistoryRepository = FakeHistoryRepository(),
        autoPauseDelayMs: Long = 3_000L,
        ble: FakeBleSensorDataSource = FakeBleSensorDataSource(),
        settings: UserSettings = this.settings
    ) = RideTracker(
        sensorDataSource = sensor,
        // warmupReliableFixes = 1 disables the GPS cold-start warm-up gate so
        // these tracker-orchestration tests see metrics from the first reliable
        // fix; warm-up itself is covered in RideMetricsCalculatorTest.
        metricsCalculator = RideMetricsCalculator(warmupReliableFixes = 1),
        historyRepository = history,
        trackPointRepository = FakeTrackPointRepository(),
        lapRepository = FakeLapRepository(),
        bleSensorDataSource = ble,
        userSettingsRepository = FakeUserSettingsRepository(settings),
        autoPauseDelayMs = autoPauseDelayMs,
        scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
    )

    private fun sampleAt(
        timestampMs: Long,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        speedFromGpsMps: Double? = null,
        accuracy: Float? = null
    ) = RideSensorSample(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        speedFromGpsMps = speedFromGpsMps,
        accuracyM = accuracy
    )
}

private class FakeSensorDataSource : RideSensorDataSource {
    val samples = MutableSharedFlow<RideSensorSample>(extraBufferCapacity = 64)
    var startResult: EmptyResult<SensorError> = Result.Success(Unit)
    var started = false
    var stopped = false

    override fun observeSamples(): Flow<RideSensorSample> = samples
    override fun start(): EmptyResult<SensorError> {
        started = true
        return startResult
    }

    override fun stop() {
        stopped = true
    }
}

private class FakeHistoryRepository : RideHistoryRepository {
    val saved = mutableListOf<RideRecord>()

    override fun observeAll(): Flow<List<RideRecord>> = MutableStateFlow(emptyList())
    override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> =
        Result.Error(DataError.Local.NOT_FOUND)

    override suspend fun save(ride: RideRecord): Result<Long, DataError.Local> {
        saved.add(ride)
        return Result.Success(saved.size.toLong())
    }

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> = Result.Success(Unit)
    override suspend fun deleteAll(): EmptyResult<DataError.Local> = Result.Success(Unit)
}

private class FakeTrackPointRepository : RideTrackPointRepository {
    val saved = mutableMapOf<Long, List<RideTrackPoint>>()

    override suspend fun savePoints(
        rideId: Long,
        points: List<RideTrackPoint>
    ): EmptyResult<DataError.Local> {
        saved[rideId] = points
        return Result.Success(Unit)
    }

    override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> =
        Result.Success(saved[rideId] ?: emptyList())
}

private class FakeLapRepository : RideLapRepository {
    val saved = mutableMapOf<Long, List<LapRecord>>()

    override suspend fun saveLaps(rideId: Long, laps: List<LapRecord>): EmptyResult<DataError.Local> {
        saved[rideId] = laps
        return Result.Success(Unit)
    }

    override suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local> =
        Result.Success(saved[rideId] ?: emptyList())
}

private class FakeBleSensorDataSource : BleSensorDataSource {
    val samples = MutableSharedFlow<BleSample>(extraBufferCapacity = 16)
    override fun observeSamples(): Flow<BleSample> = samples
    override fun connect(hrmAddress: String?, cadenceAddress: String?) = Unit
    override fun disconnect() = Unit
}

private class FakeUserSettingsRepository(
    private val settings: UserSettings
) : UserSettingsRepository {
    override fun observeSettings(): Flow<UserSettings> = MutableStateFlow(settings)
    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> = Result.Success(Unit)
}
