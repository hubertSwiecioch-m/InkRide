package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

enum class TrackingStatus {
    IDLE, TRACKING, PAUSED, AUTO_PAUSED
}

data class TrackingState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val metrics: RideMetrics = RideMetrics(),
    val laps: List<LapRecord> = emptyList(),
    val activeGoal: RideGoal? = null,
    // Loaded GPX route the rider is following, and their live progress along it.
    // Both null when no route is loaded.
    val activeRoute: PlannedRoute? = null,
    val routeProgress: RouteProgress? = null
)

/**
 * Process-scoped owner of an in-progress ride.
 *
 * Registered as a Koin singleton so the sample-collection loop runs on its own
 * [scope], independent of any ViewModel lifecycle. Paired with the foreground
 * `TrackingService` — which keeps the process alive — this lets a ride keep
 * recording while the app is backgrounded or the (E-Ink) screen is off.
 *
 * The UI observes [state] for live status/metrics and [errors] for one-shot
 * sensor failures, and issues commands via [start]/[pause]/[resume]/[stop],
 * [recordLap], and [setGoal]/[clearGoal].
 */
class RideTracker(
    private val sensorDataSource: RideSensorDataSource,
    private val metricsCalculator: RideMetricsCalculator,
    private val historyRepository: RideHistoryRepository,
    private val trackPointRepository: RideTrackPointRepository,
    private val lapRepository: RideLapRepository,
    private val bleSensorDataSource: BleSensorDataSource,
    private val userSettingsRepository: UserSettingsRepository,
    private val routeFollower: RouteFollower = RouteFollower(),
    private val minSaveDistanceKm: Double = 0.01,
    // Smallest segment (km) that closes into an automatic final lap at stop.
    private val minLapDistanceKm: Double = 0.01,
    // Auto-pause: drop into AUTO_PAUSED after the rider stays below
    // [autoPauseSpeedKmh] for [autoPauseDelayMs], and auto-resume once they
    // exceed [autoResumeSpeedKmh]. The resume threshold sits above the pause
    // threshold (hysteresis) so GPS speed wobble near a stop can't rapidly flip
    // the state back and forth.
    private val autoPauseSpeedKmh: Double = 1.5,
    private val autoResumeSpeedKmh: Double = 2.5,
    private val autoPauseDelayMs: Long = 3_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    // One-shot sensor errors (permission/hardware) surfaced to the UI. Buffered
    // so an emission isn't lost if no collector is attached at that instant.
    private val _errors = MutableSharedFlow<SensorError>(extraBufferCapacity = 1)
    val errors: SharedFlow<SensorError> = _errors.asSharedFlow()

    // Edge-triggered speed/HR alerts. The foreground service collects these to
    // vibrate the device. Buffered so a buzz isn't lost during a brief gap in
    // collection (e.g. service restart).
    private val _alerts = MutableSharedFlow<RideAlert>(extraBufferCapacity = 4)
    val alerts: SharedFlow<RideAlert> = _alerts.asSharedFlow()

    // Latched alert conditions so each threshold crossing buzzes once, not every
    // sample. Reset whenever the ride leaves TRACKING (and on (re)start). Touched
    // from the GPS and BLE collectors, so guarded by [alertLock].
    private var wasOverSpeed = false
    private var wasHrHigh = false
    private var wasHrLow = false
    private var wasOffRoute = false
    private val alertLock = Any()

    private var collectJob: Job? = null
    private var sessionStartMs: Long = 0L

    // Timestamp (sample clock) at which the rider's speed first dropped below the
    // auto-pause threshold; null whenever they're moving. Drives the delay before
    // auto-pause engages. Read/written by the collection coroutine and nulled by
    // manual start/pause/resume/stop on the caller thread, so it's @Volatile for
    // cross-thread visibility.
    @Volatile
    private var lowSpeedSinceMs: Long? = null

    // In-memory GPS track for the current ride, flushed to the database (keyed by
    // the new ride's id) at stop. Guarded by [trackPointsLock] because it's
    // appended from the sample-collection coroutine but snapshotted/cleared from
    // whichever thread calls stop().
    private val trackPoints = mutableListOf<RideTrackPoint>()
    private val trackPointsLock = Any()

    // Ride-total values captured at the previous lap boundary; the next lap is
    // recorded as the delta from these. Reset to 0 at the start of each ride.
    private var lapBaselineDistanceKm: Double = 0.0
    private var lapBaselineMovingTimeSeconds: Long = 0L
    private var lapBaselineElevationGainM: Double = 0.0

    // Latest BLE sensor reading, folded into every emitted RideMetrics. Written by
    // the BLE collector coroutine, read by the GPS collector — @Volatile for
    // cross-thread visibility.
    @Volatile
    private var latestBle: BleSample? = null

    // Latest user settings, kept current by the collection loop so metric
    // calculation always uses up-to-date weight/bike/age values.
    @Volatile
    private var latestSettings: UserSettings = UserSettings(weightKg = 75, age = 30)

    /** Starts a new ride from idle, or resumes a paused one. No-op while tracking. */
    fun start() {
        when (_state.value.status) {
            TrackingStatus.TRACKING -> Unit
            TrackingStatus.PAUSED, TrackingStatus.AUTO_PAUSED -> {
                lowSpeedSinceMs = null
                resetAlertState()
                _state.update { it.copy(status = TrackingStatus.TRACKING) }
            }
            TrackingStatus.IDLE -> startNewSession()
        }
    }

    /** Manually pauses an active or auto-paused ride. Manual pause sticks: it
     *  won't auto-resume when the rider starts moving again. */
    fun pause() {
        val status = _state.value.status
        if (status == TrackingStatus.TRACKING || status == TrackingStatus.AUTO_PAUSED) {
            lowSpeedSinceMs = null
            _state.update { it.copy(status = TrackingStatus.PAUSED) }
        }
    }

    fun resume() {
        if (_state.value.status == TrackingStatus.PAUSED) {
            lowSpeedSinceMs = null
            resetAlertState()
            _state.update { it.copy(status = TrackingStatus.TRACKING) }
        }
    }

    /**
     * Closes the current lap as the delta from the previous lap boundary and
     * appends it to the lap list. No-op while idle.
     */
    fun recordLap() {
        if (_state.value.status == TrackingStatus.IDLE) return
        val current = _state.value
        val lap = buildLap(current.metrics, current.laps.size + 1)
        lapBaselineDistanceKm = current.metrics.distanceKm
        lapBaselineMovingTimeSeconds = current.metrics.movingTimeSeconds
        lapBaselineElevationGainM = current.metrics.elevationGainM
        _state.update { it.copy(laps = it.laps + lap) }
    }

    /** Sets the per-ride goal. Persists only for the current ride. */
    fun setGoal(goal: RideGoal) {
        _state.update { it.copy(activeGoal = goal) }
    }

    /** Clears any active goal. */
    fun clearGoal() {
        _state.update { it.copy(activeGoal = null) }
    }

    /**
     * Loads a route for the rider to follow. Like a goal it survives [start] from
     * idle and is cleared on [stop]; off-route/next-turn progress is recomputed on
     * each GPS fix. Resets the off-route latch so a fresh route re-arms the alert.
     */
    fun loadRoute(route: PlannedRoute) {
        synchronized(alertLock) { wasOffRoute = false }
        _state.update { it.copy(activeRoute = route, routeProgress = null) }
    }

    /** Unloads the active route and clears its progress. */
    fun clearRoute() {
        synchronized(alertLock) { wasOffRoute = false }
        _state.update { it.copy(activeRoute = null, routeProgress = null) }
    }

    /** Stops tracking, persists the ride if it covered enough distance, and resets. */
    fun stop() {
        if (_state.value.status == TrackingStatus.IDLE) return
        val current = _state.value
        // Close the in-progress segment into a final lap so the breakdown covers
        // the whole ride — but only when the rider actually used laps.
        val laps = if (
            current.laps.isNotEmpty() &&
            current.metrics.distanceKm - lapBaselineDistanceKm >= minLapDistanceKm
        ) {
            current.laps + buildLap(current.metrics, current.laps.size + 1)
        } else {
            current.laps
        }
        saveRide(current.metrics, sessionStartMs, laps)
        collectJob?.cancel()
        collectJob = null
        sensorDataSource.stop()
        bleSensorDataSource.disconnect()
        metricsCalculator.reset()
        lowSpeedSinceMs = null
        latestBle = null
        resetAlertState()
        resetLapBaseline()
        _state.value = TrackingState()
    }

    private fun startNewSession() {
        sensorDataSource.start()
            .onFailure { _errors.tryEmit(it) }
            .onSuccess {
                // Preserve a goal/route the rider set before starting; laps always reset.
                val goal = _state.value.activeGoal
                val route = _state.value.activeRoute
                sessionStartMs = System.currentTimeMillis()
                metricsCalculator.reset()
                lowSpeedSinceMs = null
                latestBle = null
                resetAlertState()
                resetLapBaseline()
                synchronized(trackPointsLock) { trackPoints.clear() }
                _state.value = TrackingState(
                    status = TrackingStatus.TRACKING,
                    activeGoal = goal,
                    activeRoute = route
                )
                launchCollection()
            }
    }

    private fun resetLapBaseline() {
        lapBaselineDistanceKm = 0.0
        lapBaselineMovingTimeSeconds = 0L
        lapBaselineElevationGainM = 0.0
    }

    private fun buildLap(metrics: RideMetrics, lapNumber: Int): LapRecord {
        val distanceKm = (metrics.distanceKm - lapBaselineDistanceKm).coerceAtLeast(0.0)
        val movingSeconds = (metrics.movingTimeSeconds - lapBaselineMovingTimeSeconds).coerceAtLeast(0L)
        val elevationGainM = (metrics.elevationGainM - lapBaselineElevationGainM).coerceAtLeast(0.0)
        val avgSpeedKmh = if (movingSeconds > 0L) distanceKm / (movingSeconds / 3600.0) else 0.0
        return LapRecord(
            lapNumber = lapNumber,
            distanceKm = distanceKm,
            movingTimeSeconds = movingSeconds,
            averageSpeedKmh = avgSpeedKmh,
            elevationGainM = elevationGainM
        )
    }

    /**
     * Buffers a GPS fix into the in-memory track. Only points captured while
     * actively TRACKING are recorded, so pauses (manual or auto) leave clean
     * gaps in the exported GPX rather than clusters of stationary points.
     */
    private fun recordTrackPoint(status: TrackingStatus, sample: RideSensorSample, metrics: RideMetrics) {
        if (status != TrackingStatus.TRACKING) return
        val lat = sample.latitude ?: return
        val lng = sample.longitude ?: return
        synchronized(trackPointsLock) {
            trackPoints.add(
                RideTrackPoint(
                    timestampMs = sample.timestampMs,
                    latitude = lat,
                    longitude = lng,
                    altitudeM = metrics.altitudeM,
                    accuracyM = sample.accuracyM
                )
            )
        }
    }

    private fun launchCollection() {
        collectJob?.cancel()
        collectJob = scope.launch {
            val settingsJob = launch {
                userSettingsRepository.observeSettings().collect { latestSettings = it }
            }
            // Drive (re)connection only off the paired-address pair, not the whole
            // settings object — so unrelated edits (units, visible metrics) don't
            // touch the GATT layer.
            val bleConnectJob = launch {
                userSettingsRepository.observeSettings()
                    .map { it.pairedHrmAddress to it.pairedCadenceAddress }
                    .distinctUntilChanged()
                    .collect { (hrm, cadence) -> bleSensorDataSource.connect(hrm, cadence) }
            }
            // BLE notifications arrive independently of GPS fixes; fold each new
            // sample straight into the published metrics so HR/cadence stay live
            // even during a GPS dropout or while stationary.
            val bleJob = launch {
                bleSensorDataSource.observeSamples().collect { ble ->
                    latestBle = ble
                    val updated = _state.updateAndGet { current ->
                        if (current.status == TrackingStatus.IDLE) current
                        else current.copy(
                            metrics = current.metrics.copy(
                                heartRateBpm = ble.heartRateBpm,
                                cadenceRpm = ble.cadenceRpm
                            )
                        )
                    }
                    // HR alerts can fire from a BLE notification alone, with no
                    // intervening GPS fix.
                    evaluateAlerts(updated.status, updated.metrics)
                }
            }
            try {
                sensorDataSource.observeSamples().collect { sample ->
                    val statusBefore = _state.value.status
                    if (statusBefore == TrackingStatus.IDLE) return@collect
                    val isPaused = statusBefore == TrackingStatus.PAUSED ||
                        statusBefore == TrackingStatus.AUTO_PAUSED
                    val baseMetrics = metricsCalculator.process(
                        sample = sample,
                        userSettings = latestSettings,
                        isPaused = isPaused
                    )
                    // Fold the latest BLE reading into the published metrics; the
                    // calculator stays GPS-only.
                    val ble = latestBle
                    val metrics = if (ble != null) {
                        baseMetrics.copy(heartRateBpm = ble.heartRateBpm, cadenceRpm = ble.cadenceRpm)
                    } else {
                        baseMetrics
                    }
                    val autoStatus = evaluateAutoPause(statusBefore, metrics.currentSpeedKmh, sample.timestampMs)
                    // Recompute route-follow progress from this fix; carry the
                    // previous value forward on a fix-less sample so the readout
                    // doesn't flicker between GPS updates.
                    val progress = evaluateRoute(sample)
                    // A manual start/pause/resume/stop may have landed on the caller
                    // thread since statusBefore was read. Only apply the auto-pause
                    // decision when the status is unchanged, and never write over a
                    // stop() that reset us to IDLE — so the rider's explicit command
                    // is never clobbered by this (asynchronous) sample.
                    val newState = _state.updateAndGet { current ->
                        if (current.status == TrackingStatus.IDLE) return@updateAndGet current
                        val resolved = if (current.status == statusBefore) autoStatus else current.status
                        current.copy(status = resolved, metrics = metrics, routeProgress = progress)
                    }
                    recordTrackPoint(newState.status, sample, metrics)
                    evaluateAlerts(newState.status, newState.metrics)
                    evaluateOffRoute(newState.status, newState.routeProgress)
                }
            } finally {
                settingsJob.cancel()
                bleConnectJob.cancel()
                bleJob.cancel()
            }
        }
    }

    /**
     * Decides the next status based on the live speed, applying the auto-pause
     * delay and the pause/resume hysteresis. Only ever transitions between
     * TRACKING and AUTO_PAUSED — a manual PAUSED (or IDLE) is left untouched so
     * the rider's explicit choice always wins.
     */
    private fun evaluateAutoPause(
        current: TrackingStatus,
        speedKmh: Double,
        nowMs: Long
    ): TrackingStatus = when (current) {
        TrackingStatus.TRACKING -> {
            if (speedKmh < autoPauseSpeedKmh) {
                val since = lowSpeedSinceMs ?: nowMs.also { lowSpeedSinceMs = it }
                if (nowMs - since >= autoPauseDelayMs) {
                    lowSpeedSinceMs = null
                    TrackingStatus.AUTO_PAUSED
                } else {
                    current
                }
            } else {
                lowSpeedSinceMs = null
                current
            }
        }
        TrackingStatus.AUTO_PAUSED -> {
            if (speedKmh > autoResumeSpeedKmh) TrackingStatus.TRACKING else current
        }
        else -> current
    }

    private fun resetAlertState() = synchronized(alertLock) {
        wasOverSpeed = false
        wasHrHigh = false
        wasHrLow = false
        wasOffRoute = false
    }

    /**
     * Emits an alert the first time a live metric crosses a configured threshold
     * (edge-triggered), so the rider is buzzed once per crossing rather than on
     * every sample. Alerts only fire while actively TRACKING; any other status
     * (paused/idle) clears the latches so the next active crossing re-arms.
     */
    private fun evaluateAlerts(status: TrackingStatus, metrics: RideMetrics) {
        val config = latestSettings.alerts
        synchronized(alertLock) {
            if (status != TrackingStatus.TRACKING || !config.hasAny) {
                wasOverSpeed = false
                wasHrHigh = false
                wasHrLow = false
                return
            }

            val maxSpeed = config.maxSpeedKmh
            if (maxSpeed != null) {
                val over = metrics.currentSpeedKmh > maxSpeed
                if (over && !wasOverSpeed) _alerts.tryEmit(RideAlert.OverSpeed(metrics.currentSpeedKmh))
                wasOverSpeed = over
            } else {
                wasOverSpeed = false
            }

            val hr = metrics.heartRateBpm
            val hrMax = config.hrZoneMaxBpm
            if (hr != null && hrMax != null) {
                val high = hr > hrMax
                if (high && !wasHrHigh) _alerts.tryEmit(RideAlert.HeartRateHigh(hr))
                wasHrHigh = high
            } else {
                wasHrHigh = false
            }

            val hrMin = config.hrZoneMinBpm
            if (hr != null && hrMin != null) {
                val low = hr < hrMin
                if (low && !wasHrLow) _alerts.tryEmit(RideAlert.HeartRateLow(hr))
                wasHrLow = low
            } else {
                wasHrLow = false
            }
        }
    }

    /**
     * Recomputes progress along the loaded route for the given fix. Returns the
     * previous progress unchanged when no route is loaded or the sample carries
     * no usable position.
     */
    private fun evaluateRoute(sample: RideSensorSample): RouteProgress? {
        val route = _state.value.activeRoute ?: return null
        val lat = sample.latitude
        val lng = sample.longitude
        if (lat == null || lng == null) return _state.value.routeProgress
        return routeFollower.evaluate(route, lat, lng)
    }

    /**
     * Edge-triggered off-route alert: buzzes once when the rider first strays
     * beyond the threshold, re-arming after they return. Only fires while
     * actively TRACKING; any other status clears the latch.
     */
    private fun evaluateOffRoute(status: TrackingStatus, progress: RouteProgress?) {
        synchronized(alertLock) {
            if (status != TrackingStatus.TRACKING || progress == null) {
                wasOffRoute = false
                return
            }
            val off = progress.isOffRoute
            if (off && !wasOffRoute) _alerts.tryEmit(RideAlert.OffRoute(progress.distanceToRouteM))
            wasOffRoute = off
        }
    }

    private fun saveRide(metrics: RideMetrics, startedAt: Long, laps: List<LapRecord>) {
        // Snapshot and clear the track buffer up front (synchronously, before the
        // collection job is cancelled) so a too-short ride still drops its points
        // and the next ride starts clean.
        val points = synchronized(trackPointsLock) {
            ArrayList(trackPoints).also { trackPoints.clear() }
        }
        if (metrics.distanceKm < minSaveDistanceKm) return
        val endedAt = System.currentTimeMillis()
        val settings = latestSettings
        scope.launch {
            historyRepository.save(
                RideRecord(
                    id = 0L,
                    startTimestamp = startedAt,
                    endTimestamp = endedAt,
                    distanceKm = metrics.distanceKm,
                    movingTimeSeconds = metrics.movingTimeSeconds,
                    elapsedTimeSeconds = metrics.elapsedTimeSeconds,
                    averageSpeedKmh = metrics.averageSpeedKmh,
                    maxSpeedKmh = metrics.maxSpeedKmh,
                    elevationGainM = metrics.elevationGainM,
                    caloriesKcal = metrics.caloriesKcal,
                    averagePowerWatts = metrics.averagePowerWatts,
                    bikeWeightKg = settings.bikeWeightKg,
                    bikeType = settings.bikeType
                )
            ).onSuccess { rideId ->
                if (points.isNotEmpty()) {
                    trackPointRepository.savePoints(rideId, points)
                }
                if (laps.isNotEmpty()) {
                    lapRepository.saveLaps(rideId, laps)
                }
            }
        }
    }
}
