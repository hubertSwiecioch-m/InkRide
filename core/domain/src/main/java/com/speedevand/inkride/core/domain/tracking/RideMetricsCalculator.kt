package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.settings.UserSettings
import kotlin.collections.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class RideMetricsCalculator(
    private val caloriesEstimator: CaloriesEstimator = CaloriesEstimator(),
    private val powerEstimator: PowerEstimator = PowerEstimator(),
    private val weatherTrendCalculator: WeatherTrendCalculator = WeatherTrendCalculator(),
    private val autoPauseThresholdKmh: Double = 1.5,
    private val minGradeDistanceM: Double = 20.0,
    private val elevationNoiseThresholdM: Double = 1.0,
    private val maxReliableAccuracyM: Double = 20.0,
    private val maxPlausibleSpeedMps: Double = 40.0, // 144 km/h — generous for downhill cycling, rejects GPS glitches
    private val maxPlausibleAccelMps2: Double = 8.0, // ~0.8g — beyond what cycling can produce
    // GPS-vs-distance cross-validation: when both GPS speed and distance-speed
    // are available, reject the segment if distance-speed exceeds this ratio
    // of GPS speed AND exceeds this minimum absolute speed (m/s).
    private val crossValidationMaxRatio: Double = 3.0,
    private val crossValidationMinSpeedMps: Double = 10.0,
    // Bounce detection: if a position jumps > this distance from the
    // second-to-last position and returns within bounceReturnRadiusM of the
    // third-to-last position, it's a bounce artifact.
    private val bounceJumpRadiusM: Double = 30.0,
    private val bounceReturnRadiusM: Double = 5.0,
    // Barometric altitude sanity range (meters). Values outside this are
    // treated as sensor errors and discarded.
    private val minPlausibleBaroAltitudeM: Double = -500.0,
    private val maxPlausibleBaroAltitudeM: Double = 9000.0,
    // Maximum GPS-fix interval (ms) credited to time-integrated metrics
    // (moving time, calories, power). When a fix arrives after a long GPS
    // dropout, the elapsed gap is unknown — capping it prevents fabricating
    // moving time and energy for a period we have no movement data for.
    private val maxIntegrationGapMs: Long = 10_000L,
    // GPS cold-start warm-up: number of consecutive reliable (accuracy ≤
    // [maxReliableAccuracyM]) fixes required before the receiver is trusted.
    // The earliest fixes after start can report a good-looking accuracy while
    // carrying a wildly inflated Doppler speed (and a position that "snaps"
    // toward truth, fabricating displacement). Holding all movement-derived
    // metrics until a short streak confirms convergence keeps the speedometer
    // honest for the first second or two of a ride. A streak of one disables
    // the gate entirely.
    private val warmupReliableFixes: Int = 3,
    // Upper bound (ms, measured from the first location fix) on the warm-up
    // window. A ride whose GPS never reaches [maxReliableAccuracyM] (heavy tree
    // cover, urban canyon, a cheap receiver) would otherwise never complete the
    // streak above and stay frozen at zero for its whole duration. After this
    // window we trust whatever fixes arrive — the brief cold-start suppression
    // is bounded, and movement is recorded for the rest of the ride.
    private val warmupMaxDurationMs: Long = 10_000L
) {
    private var sessionStartMs: Long? = null
    private var lastSample: RideSensorSample? = null
    private var lastLocationSample: RideSensorSample? = null
    private var movingTimeMs: Long = 0L
    private var totalDistanceM: Double = 0.0
    private var maxSpeedMps: Double = 0.0
    private var elevationGainM: Double = 0.0
    private var smoothedAltitudeM: Double? = null
    private var lastElevationGainAltitudeM: Double? = null
    private var caloriesKcal: Double = 0.0
    private var lastSpeedMps: Double = 0.0
    // Last speed reported to the UI. Carried forward on non-location samples
    // (barometer/heading) so the speedometer doesn't flicker to 0 between the
    // ~1 Hz GPS fixes — important on slow-refresh E-Ink displays.
    private var lastReportedSpeedMps: Double = 0.0
    private var currentPowerWatts: Int = 0
    // Time-weighted average power over MOVING time. Sample emission is irregular
    // (GPS ~1 Hz, barometer ~2 Hz, heading bursty), so a simple per-sample mean
    // would be biased by sample rate. Weighting by elapsed moving time fixes that
    // and excludes stopped (zero-power) periods.
    private var powerWeightedSumWattMs: Double = 0.0
    private var powerDurationMs: Long = 0L

    // Barometer/GPS complementary filter: barometer for short-term precision,
    // GPS for long-term drift correction (weather pressure changes ~1 hPa/h ≈ 8.4 m/h).
    private var baroGpsOffsetM: Double? = null

    // Acceleration smoothing to reduce derivative noise from GPS speed.
    private var smoothedAccelMps2: Double = 0.0

    private val gradePoints = ArrayDeque<Pair<Double, Double>>()
    private var currentGrade: Double = 0.0

    // Bounce detection: track the last 3 position-bearing samples (lat, lng).
    // When GPS jumps away and immediately returns, the middle sample is an
    // artifact that should be rejected.
    private val recentPositions = ArrayDeque<Pair<Double, Double>>(3)

    // Stationary-drift protection: counts consecutive samples where the rider
    // appears stationary. After 5+ stationary samples, require 2 consecutive
    // "moving" confirmations before resuming distance accumulation.
    private var consecutiveStationarySamples: Int = 0
    private var movingConfirmationsNeeded: Int = 0

    // GPS cold-start warm-up state (see [warmupReliableFixes]). The streak
    // counts consecutive reliable fixes and resets on any unreliable one;
    // once it reaches the threshold the receiver is considered converged for
    // the rest of the session.
    private var consecutiveReliableFixes: Int = 0
    private var isGpsWarmedUp: Boolean = false
    // Timestamp of the first location fix this session — anchors the warm-up
    // timeout fallback so it can't suppress movement indefinitely.
    private var firstLocationFixMs: Long? = null

    fun reset() {
        sessionStartMs = null
        lastSample = null
        lastLocationSample = null
        movingTimeMs = 0L
        totalDistanceM = 0.0
        maxSpeedMps = 0.0
        elevationGainM = 0.0
        smoothedAltitudeM = null
        lastElevationGainAltitudeM = null
        caloriesKcal = 0.0
        lastSpeedMps = 0.0
        lastReportedSpeedMps = 0.0
        currentPowerWatts = 0
        powerWeightedSumWattMs = 0.0
        powerDurationMs = 0L
        baroGpsOffsetM = null
        smoothedAccelMps2 = 0.0
        gradePoints.clear()
        currentGrade = 0.0
        recentPositions.clear()
        consecutiveStationarySamples = 0
        movingConfirmationsNeeded = 0
        consecutiveReliableFixes = 0
        isGpsWarmedUp = false
        firstLocationFixMs = null
        weatherTrendCalculator.reset()
    }

    fun process(sample: RideSensorSample, userSettings: UserSettings, isPaused: Boolean = false): RideMetrics {
        val startTime = sessionStartMs ?: sample.timestampMs.also { sessionStartMs = it }
        val previous = lastSample

        // Feed the barometer into the weather-trend window on every sample
        // (movement-independent — pressure changes whether moving or stopped).
        sample.pressureHpa?.let { weatherTrendCalculator.add(sample.timestampMs, it) }
        val weatherTrend = weatherTrendCalculator.trend()

        if (previous == null) {
            val rawAlt = fusedAltitude(sample, dtMs = 0L)
            smoothedAltitudeM = rawAlt
            lastElevationGainAltitudeM = rawAlt
            lastSample = sample
            if (sample.latitude != null && sample.longitude != null) {
                lastLocationSample = sample
            }
            return RideMetrics(
                altitudeM = smoothedAltitudeM,
                elapsedTimeSeconds = 0L,
                gpsAccuracyM = sample.accuracyM,
                bearingDegrees = sample.bearingDegrees,
                weatherTrend = weatherTrend
            )
        }

        val dtMs = (sample.timestampMs - previous.timestampMs).coerceAtLeast(0L)

        // Movement, speed, distance, calories and power can only be derived from
        // GPS fixes. Barometer/heading-only samples (which interleave with the
        // ~1 Hz GPS stream at a higher rate) carry no position, so all movement
        // logic is gated behind this flag. Feeding them through would falsely
        // trip stationary-drift suppression and bias time-integrated metrics.
        val isLocationSample = sample.latitude != null && sample.longitude != null

        // Default outputs carried over from the last GPS-derived state so that
        // intermediate non-location samples don't reset the live readout.
        var speedMps = lastReportedSpeedMps
        var isActuallyMoving = false

        // Set when a location fix is rejected as a positional outlier. Such a fix
        // must not be adopted as the positional reference for the next segment,
        // nor enter the bounce-detection window (see end of method).
        var locationOutlierRejected = false

        if (isLocationSample) {
            // ── Distance between GPS fixes ──────────────────────────────────
            // Use lastLocationSample so position deltas match the actual fix
            // interval, not the arbitrary (faster) sensor sample interval.
            val (segmentDistanceM, locationDtMs) = if (lastLocationSample != null) {
                val dist = haversineDistanceMeters(
                    lastLocationSample!!.latitude!!,
                    lastLocationSample!!.longitude!!,
                    sample.latitude,
                    sample.longitude
                )
                val ldt = (sample.timestampMs - lastLocationSample!!.timestampMs).coerceAtLeast(0L)
                dist to ldt
            } else {
                0.0 to 0L
            }

            // ── GPS outlier rejection ──────────────────────────────────────
            val locationSpeedMps = if (locationDtMs > 0L) segmentDistanceM / (locationDtMs / 1000.0) else 0.0
            val isSpeedOutlier = locationSpeedMps > maxPlausibleSpeedMps

            // Acceleration sanity check: reject segments whose implied
            // acceleration exceeds what's physically plausible for cycling.
            // Only active when we have a meaningful speed reference (> 0) —
            // otherwise the first movement from standstill would always trip it.
            val isAccelOutlier = if (locationDtMs > 0L && lastSpeedMps > 0.0) {
                abs(locationSpeedMps - lastSpeedMps) / (locationDtMs / 1000.0) > maxPlausibleAccelMps2
            } else false

            // Bounce detection: GPS sometimes jumps to a distant point and
            // immediately returns. When the new position is close to the one
            // from 3 fixes ago but the intermediate fix was far away, the
            // current segment is the "return" leg.
            val isBounce: Boolean = if (recentPositions.size >= 3) {
                val oldest = recentPositions.first()
                val middle = recentPositions[1]
                val jumpDist = haversineDistanceMeters(oldest.first, oldest.second, middle.first, middle.second)
                val returnDist = haversineDistanceMeters(oldest.first, oldest.second, sample.latitude, sample.longitude)
                jumpDist > bounceJumpRadiusM && returnDist < bounceReturnRadiusM
            } else {
                false
            }

            // GPS speed vs. distance-speed cross-validation. When GPS (Doppler)
            // speed is reliable but distance-speed is wildly different, the
            // position is likely a glitch — reject the segment.
            val gpsSpeedForCheck = sample.speedFromGpsMps
            val isCrossValidationFail = gpsSpeedForCheck != null &&
                sample.accuracyM?.toDouble()?.let { it <= maxReliableAccuracyM } == true &&
                locationDtMs > 0L &&
                locationSpeedMps > gpsSpeedForCheck * crossValidationMaxRatio &&
                locationSpeedMps > crossValidationMinSpeedMps

            val isOutlier = isSpeedOutlier || isAccelOutlier || isBounce || isCrossValidationFail
            locationOutlierRejected = isOutlier
            val effectiveSegmentDistanceM = if (isOutlier) 0.0 else segmentDistanceM

            // ── Accuracy checks ────────────────────────────────────────────
            // Default to UNRELIABLE when accuracy is unknown — never assume perfect.
            val hasReliableCurrentAccuracy = sample.accuracyM?.toDouble()?.let { it <= maxReliableAccuracyM } ?: false
            val hasReliablePreviousAccuracy = lastLocationSample?.accuracyM?.toDouble()?.let { it <= maxReliableAccuracyM } ?: false
            val combinedAccuracyM = max(
                lastLocationSample?.accuracyM?.toDouble() ?: 0.0,
                sample.accuracyM?.toDouble() ?: 0.0
            )

            // ── GPS cold-start warm-up ─────────────────────────────────────
            // Count consecutive reliable fixes; a single unreliable fix breaks
            // the streak. The receiver is trusted only once the streak reaches
            // [warmupReliableFixes]. Until then the Doppler speed and position
            // delta are both suspect (the fix is still converging), so all
            // movement is suppressed below.
            if (!isGpsWarmedUp) {
                val firstFixMs = firstLocationFixMs ?: sample.timestampMs.also { firstLocationFixMs = it }
                if (hasReliableCurrentAccuracy) {
                    consecutiveReliableFixes++
                } else {
                    consecutiveReliableFixes = 0
                }
                // Complete warm-up on a confirmed convergence streak, or once the
                // bounded fallback window elapses — so a never-reliable ride still
                // records movement instead of staying frozen at zero.
                if (consecutiveReliableFixes >= warmupReliableFixes ||
                    sample.timestampMs - firstFixMs >= warmupMaxDurationMs
                ) {
                    isGpsWarmedUp = true
                }
            }

            // ── Auto-pause detection ───────────────────────────────────────
            // Clamp Doppler speed to a plausible range: a single glitched fix
            // (even with "good" accuracy) must not poison max speed or display.
            val speedMpsFromGps = if (hasReliableCurrentAccuracy) {
                sample.speedFromGpsMps?.takeIf { it in 0.0..maxPlausibleSpeedMps }
            } else null
            val speedKmhFromGps = (speedMpsFromGps ?: 0.0) * 3.6

            val isMovingBasedOnGps = speedKmhFromGps >= autoPauseThresholdKmh
            val isSignificantMovement = effectiveSegmentDistanceM > (combinedAccuracyM * 0.5)

            // ── Stationary-drift protection ────────────────────────────────
            // When the rider appears stationary for several consecutive fixes,
            // require 2 consecutive "moving" confirmations before accumulating
            // distance. This prevents single-fix GPS wobble from adding false
            // distance when the device is truly stationary.
            val isAppearsStationary = !isMovingBasedOnGps && !isSignificantMovement
            if (isAppearsStationary) {
                consecutiveStationarySamples++
                if (consecutiveStationarySamples >= 5) {
                    movingConfirmationsNeeded = 2
                }
            } else {
                consecutiveStationarySamples = 0
                if (movingConfirmationsNeeded > 0) {
                    movingConfirmationsNeeded--
                }
            }

            val effectiveDistanceM = when {
                // Suppress all displacement until the GPS fix has converged.
                !isGpsWarmedUp -> 0.0
                isMovingBasedOnGps || isSignificantMovement ->
                    if (movingConfirmationsNeeded > 0) 0.0 else effectiveSegmentDistanceM
                else -> 0.0
            }

            // Speed from distance uses the location-fix interval.
            val speedMpsFromDistance = if (locationDtMs > 0L && hasReliableCurrentAccuracy && hasReliablePreviousAccuracy) {
                effectiveDistanceM / (locationDtMs / 1000.0)
            } else {
                0.0
            }

            speedMps = speedMpsFromGps ?: speedMpsFromDistance
            isActuallyMoving = isMovingBasedOnGps || isSignificantMovement

            // During warm-up neither the Doppler speed nor the position delta is
            // trustworthy — hold the live readout at zero rather than flashing a
            // cold-start over-reading and poisoning max speed.
            if (!isGpsWarmedUp) {
                speedMps = 0.0
                isActuallyMoving = false
            }

            // Interval credited to time-integrated metrics. Use the GPS-fix
            // interval (not dtMs, which would only span the gap since the last
            // — possibly barometer — sample), capped so a fix returning from a
            // long dropout can't fabricate moving time/energy.
            val integrationDtMs = locationDtMs.coerceAtMost(maxIntegrationGapMs)

            if (!isPaused) {
                if (isActuallyMoving) {
                    movingTimeMs += integrationDtMs
                }
                totalDistanceM += effectiveDistanceM
                maxSpeedMps = max(maxSpeedMps, speedMps)
                caloriesKcal += caloriesEstimator.estimateKcal(
                    speedKmh = speedMps * 3.6,
                    intervalMs = integrationDtMs,
                    userSettings = userSettings,
                    gradePercent = currentGrade
                )

                // EMA-smoothed acceleration (alpha = 0.3) over the fix interval
                // to reduce GPS-derivative noise.
                val rawAccel = if (locationDtMs > 0L) (speedMps - lastSpeedMps) / (locationDtMs / 1000.0) else 0.0
                smoothedAccelMps2 = if (smoothedAccelMps2 == 0.0 && rawAccel != 0.0) {
                    rawAccel
                } else {
                    smoothedAccelMps2 + 0.3 * (rawAccel - smoothedAccelMps2)
                }

                currentPowerWatts = powerEstimator.estimateWatts(
                    speedMps = speedMps,
                    accelerationMps2 = smoothedAccelMps2,
                    gradePercent = currentGrade,
                    userSettings = userSettings
                )
                if (isActuallyMoving) {
                    powerWeightedSumWattMs += currentPowerWatts.toDouble() * integrationDtMs
                    powerDurationMs += integrationDtMs
                }
                lastSpeedMps = speedMps
            } else {
                currentPowerWatts = 0
            }

            lastReportedSpeedMps = speedMps
        }

        val speedKmh = speedMps * 3.6

        // ── Altitude: barometer/GPS fusion + EMA smoothing ──────────────────
        val rawAltitudeM = fusedAltitude(sample, dtMs)
        if (rawAltitudeM != null) {
            // Smooth altitude (EMA) — barometer is cleaner, GPS needs more smoothing.
            val alpha = if (sample.altitudeFromBarometerM != null) 0.5 else 0.1
            smoothedAltitudeM = smoothedAltitudeM?.let { it + alpha * (rawAltitudeM - it) } ?: rawAltitudeM

            val currentSmoothed = smoothedAltitudeM!!

            if (!isPaused && isActuallyMoving) {
                // Elevation gain with descent hysteresis:
                // Only count sustained ascents above noise threshold.
                // Descents smaller than 3× noise threshold do NOT reset the base —
                // this prevents overcounting on undulating terrain.
                val refAlt = lastElevationGainAltitudeM
                if (refAlt == null) {
                    lastElevationGainAltitudeM = currentSmoothed
                } else {
                    if (currentSmoothed > refAlt + elevationNoiseThresholdM) {
                        elevationGainM += (currentSmoothed - refAlt)
                        lastElevationGainAltitudeM = currentSmoothed
                    } else if (currentSmoothed < refAlt - elevationNoiseThresholdM * 3) {
                        // Significant descent (>3m): reset baseline.
                        // Micro-dips from GPS noise or small undulations are ignored.
                        lastElevationGainAltitudeM = currentSmoothed
                    }
                }

                // ── Grade from sliding window ──────────────────────────────
                gradePoints.addLast(totalDistanceM to currentSmoothed)
                // Keep window ~2× minGradeDistanceM for a stable reading.
                while (gradePoints.size > 2 && totalDistanceM - gradePoints.first().first > minGradeDistanceM * 2) {
                    gradePoints.removeFirst()
                }

                if (gradePoints.size >= 2) {
                    val first = gradePoints.first()
                    val last = gradePoints.last()
                    val dx = last.first - first.first
                    val dy = last.second - first.second

                    // Reduced minimum from 5.0m to 3.0m for faster cold-start response.
                    if (dx > 3.0) {
                        currentGrade = (dy / dx * 100.0).coerceIn(-35.0, 35.0)
                    }
                }
            }
        }

        lastSample = sample
        // Adopt this fix as the positional reference only when it's a usable
        // location sample. A fix rejected as a positional outlier must NOT become
        // the reference: otherwise the next segment's distance/speed would be
        // measured from a known-bad position, and the glitch would corrupt the
        // bounce-detection window. Keeping the last good fix means the next valid
        // fix is correctly measured across the (capped) gap instead.
        if (sample.latitude != null && sample.longitude != null && !locationOutlierRejected) {
            lastLocationSample = sample
            // Maintain a ring buffer of the last 3 GPS positions for bounce detection.
            recentPositions.addLast(sample.latitude to sample.longitude)
            while (recentPositions.size > 3) {
                recentPositions.removeFirst()
            }
        }

        val elapsedSeconds = ((sample.timestampMs - startTime) / 1000L).coerceAtLeast(0L)
        val movingSeconds = movingTimeMs / 1000L
        val avgSpeedKmh = if (movingTimeMs > 0L) {
            (totalDistanceM / (movingTimeMs / 1000.0)) * 3.6
        } else {
            0.0
        }

        val avgPower = if (powerDurationMs > 0L) (powerWeightedSumWattMs / powerDurationMs).toInt() else 0

        val quality = computeGpsQuality(sample.accuracyM, sample.satelliteCount)

        return RideMetrics(
            currentSpeedKmh = speedKmh,
            averageSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedMps * 3.6,
            distanceKm = totalDistanceM / 1000.0,
            movingTimeSeconds = movingSeconds,
            elapsedTimeSeconds = elapsedSeconds,
            altitudeM = smoothedAltitudeM,
            elevationGainM = elevationGainM,
            gradePercent = currentGrade,
            caloriesKcal = caloriesKcal,
            powerWatts = currentPowerWatts,
            averagePowerWatts = avgPower,
            gpsAccuracyM = sample.accuracyM,
            bearingDegrees = sample.bearingDegrees ?: previous.bearingDegrees,
            gpsQuality = quality,
            weatherTrend = weatherTrend
        )
    }

    /**
     * Fuses barometer and GPS altitude with a complementary filter:
     * - Barometer: precise short-term, drifts with weather (~8.4 m/hPa).
     * - GPS: noisy short-term, drift-free long-term.
     *
     * The offset between barometer and GPS is tracked with a ~10-minute time
     * constant, correcting barometric drift without introducing GPS noise.
     */
    private fun fusedAltitude(sample: RideSensorSample, dtMs: Long): Double? {
        // Clamp barometric altitude to plausible cycling range.
        // Values outside [-500, 9000] meters indicate sensor errors.
        val baro = sample.altitudeFromBarometerM?.let {
            if (it in minPlausibleBaroAltitudeM..maxPlausibleBaroAltitudeM) it else null
        }
        val gps = sample.altitudeFromGpsM

        return when {
            // Both available — fuse them.
            baro != null && gps != null -> {
                if (baroGpsOffsetM == null) {
                    baroGpsOffsetM = gps - baro
                } else if (dtMs > 0L) {
                    // Long time constant (~10 min) to slowly correct drift.
                    val alphaOffset = (dtMs / 600_000.0).coerceAtMost(0.05)
                    baroGpsOffsetM = baroGpsOffsetM!! + alphaOffset * ((gps - baro) - baroGpsOffsetM!!)
                }
                baro + baroGpsOffsetM!!
            }
            baro != null -> baro
            gps != null -> gps
            else -> null
        }
    }


    /**
     * Computes a human-readable GPS quality tier from accuracy and satellite count.
     *
     * - GOOD:  accuracy ≤ 10m with ≥ 6 satellites — trustworthy for all metrics.
     * - FAIR:  accuracy ≤ 20m, or accuracy ≤ 30m with ≥ 4 satellites — usable but
     *          exercise caution with instantaneous speed/grade.
     * - POOR:  everything else — GPS data may be unreliable; metrics relying on
     *          position deltas should be treated as approximate.
     */
    private fun computeGpsQuality(accuracyM: Float?, satelliteCount: Int?): GpsQuality {
        val acc = accuracyM?.toDouble() ?: return GpsQuality.POOR
        val sats = satelliteCount ?: 0
        return when {
            acc <= 10.0 && sats >= 6 -> GpsQuality.GOOD
            acc <= 20.0 || (acc <= 30.0 && sats >= 4) -> GpsQuality.FAIR
            else -> GpsQuality.POOR
        }
    }

    private fun haversineDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()

        val a = sin(dLat / 2).pow(2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) *
            sin(dLon / 2).pow(2)

        val c = 2 * asin(min(1.0, sqrt(a)))
        return earthRadiusM * c
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}
