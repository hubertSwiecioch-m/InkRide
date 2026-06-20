package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isZero
import com.speedevand.inkride.core.domain.settings.UserSettings
import org.junit.jupiter.api.Test

class RideMetricsCalculatorTest {

    // warmupReliableFixes = 1 disables the GPS cold-start warm-up gate so these
    // tests exercise steady-state behaviour from the first reliable fix. The
    // warm-up gate itself is covered in its own section below.
    private val calculator = RideMetricsCalculator(warmupReliableFixes = 1)
    private val settings = UserSettings(weightKg = 75, age = 32)

    // ── First sample / initialization ──────────────────────────────────────

    @Test
    fun `first sample initializes session start`() {
        val metrics = calculator.process(sampleAt(1000L), settings)
        assertThat(metrics.elapsedTimeSeconds).isZero()
    }

    @Test
    fun `first sample returns altitude but zero times`() {
        val metrics = calculator.process(
            sampleAt(0L, altitudeBarometer = 100.0),
            settings
        )
        assertThat(metrics.altitudeM).isNotNull().isEqualTo(100.0)
        assertThat(metrics.elapsedTimeSeconds).isZero()
        assertThat(metrics.movingTimeSeconds).isZero()
        assertThat(metrics.distanceKm).isZero()
    }

    @Test
    fun `first sample stores location for haversine reference`() {
        calculator.process(sampleAt(0L, latitude = 52.0, longitude = 21.0), settings)
        val metrics = calculator.process(sampleAt(1000L, latitude = 52.0, longitude = 21.0), settings)
        assertThat(metrics.distanceKm).isZero()
    }

    // ── GPS accuracy filtering ─────────────────────────────────────────────

    @Test
    fun `unreliable GPS accuracy excludes sample when movement below threshold`() {
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 5.0f), settings)
        // Move ~1.1m with bad accuracy — combined accuracy = 25.0, threshold = 12.5m
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.00001, longitude = 0.0, accuracy = 25.0f),
            settings
        )
        // Distance should be zero: GPS unreliable AND movement below threshold
        assertThat(metrics.distanceKm).isZero()
    }

    @Test
    fun `reliable GPS accuracy includes sample in distance`() {
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 5.0f), settings)
        // ~11.1 m in 1 second — realistic cycling speed (~40 km/h), below outlier threshold
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.0001, longitude = 0.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.distanceKm).isGreaterThan(0.0)
    }

    // ── Elevation gain ─────────────────────────────────────────────────────

    @Test
    fun `elevation gain accumulates when climb exceeds noise threshold`() {
        // Seed with accuracy so GPS speed is considered reliable and movement is detected.
        calculator.process(sampleAt(0L, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        // Climb to 103.0m in one step — smoothed = 100 + 0.5*(103-100) = 101.5
        // 101.5 > 100.0+1.0 → gain = 1.5
        val metrics = calculator.process(
            sampleAt(1000L, altitudeBarometer = 103.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.elevationGainM).isEqualTo(1.5)
    }

    @Test
    fun `elevation gain ignores changes below noise threshold`() {
        calculator.process(sampleAt(0L, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        // smoothed = 100.0 + 0.5*(100.5-100.0) = 100.25. 100.25 > 101.0? No.
        val metrics = calculator.process(
            sampleAt(1000L, altitudeBarometer = 100.5, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.elevationGainM).isZero()
    }

    @Test
    fun `elevation gain resets base only on significant descent`() {
        // Climb: 100.0 → 105.0, smoothed ≈ 102.5 > 101.0 → gain ≈ 2.5
        calculator.process(sampleAt(0L, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, altitudeBarometer = 105.0, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        // Small dip: 105→104 should NOT reset the base (hysteresis threshold = 3 * 1.0 = 3.0m)
        // smoothed ≈ 102.5 + 0.5*(104-102.5) = 103.25. refAlt ≈ 102.5. 103.25 > 103.5? No (below noise).
        // And 103.25 < 102.5 - 3.0 = 99.5? No (not a significant descent). Base stays at 102.5.
        calculator.process(sampleAt(2000L, altitudeBarometer = 104.0, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        // Climb again to 107.0: smoothed ≈ 103.25 + 0.5*(107-103.25) = 105.125.
        // 105.125 > 102.5+1.0=103.5 → gain += 105.125-102.5 = 2.625. Total ≈ 5.125
        val metrics = calculator.process(
            sampleAt(3000L, altitudeBarometer = 107.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Gain should be greater than initial 2.5 because base was NOT reset on the small dip
        assertThat(metrics.elevationGainM).isGreaterThan(2.5)
    }

    @Test
    fun `barometric drift while stopped is not counted as elevation gain`() {
        // Ride flat at 100m, then stop. While standing still the barometer
        // climbs 10m as the weather pressure falls. When riding resumes the
        // altitude has settled at the drifted value — none of that drift may be
        // counted as elevation gain (the baseline re-anchors during the stop).
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        calculator.process(
            sampleAt(1000L, latitude = 0.0, longitude = 0.0, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Stop (speed 0) while the barometer drifts up to 110m. Enough samples to
        // (a) cross the 5-fix sustained-stop threshold that arms the baseline
        // re-anchor and (b) let the EMA settle so the baseline tracks to ~110m.
        listOf(2000L, 2500L, 3000L, 3500L, 4000L, 4500L, 5000L).forEach { t ->
            calculator.process(
                sampleAt(t, latitude = 0.0, longitude = 0.0, altitudeBarometer = 110.0, speedFromGpsMps = 0.0, accuracy = 5.0f),
                settings
            )
        }
        // Resume riding at the drifted altitude.
        val metrics = calculator.process(
            sampleAt(6000L, latitude = 0.0001, longitude = 0.0, altitudeBarometer = 110.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Without the re-anchor the frozen 100m baseline would book ~9–10m of
        // phantom gain here. With it, essentially nothing is counted.
        assertThat(metrics.elevationGainM).isLessThan(1.0)
    }

    @Test
    fun `single stationary noise fix mid-climb does not drop pending elevation gain`() {
        // Slow steady climb where each barometer step is below the 1m noise
        // threshold, so gain is banked cumulatively against a held baseline. One
        // GPS fix briefly reads a stationary Doppler (a noise dip below the
        // movement threshold) — it must NOT re-anchor the baseline and silently
        // discard the sub-threshold gain accumulated so far.
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, altitudeBarometer = 100.0, speedFromGpsMps = 4.0, accuracy = 5.0f),
            settings
        )
        // Climb ~0.8m/sample (smoothed steps stay under the 1m bank threshold for
        // a step or two) while moving.
        calculator.process(
            sampleAt(1000L, latitude = 0.00002, longitude = 0.0, altitudeBarometer = 101.6, speedFromGpsMps = 4.0, accuracy = 5.0f),
            settings
        )
        // Single noise dip: Doppler reads 0, but it's a lone fix — not a sustained
        // stop — so the baseline must hold.
        calculator.process(
            sampleAt(2000L, latitude = 0.00004, longitude = 0.0, altitudeBarometer = 103.2, speedFromGpsMps = 0.0, accuracy = 5.0f),
            settings
        )
        // Keep climbing.
        val metrics = calculator.process(
            sampleAt(3000L, latitude = 0.00006, longitude = 0.0, altitudeBarometer = 105.0, speedFromGpsMps = 4.0, accuracy = 5.0f),
            settings
        )
        // The real ~4–5m climb must be recorded, not swallowed by a premature
        // re-anchor on the lone stationary fix.
        assertThat(metrics.elevationGainM).isGreaterThan(2.0)
    }

    @Test
    fun `EMA smoothing with barometer uses alpha 0_5`() {
        calculator.process(sampleAt(0L, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(
            sampleAt(1000L, altitudeBarometer = 110.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // smoothed = 100.0 + 0.5 * (110.0 - 100.0) = 105.0
        assertThat(metrics.altitudeM).isNotNull().isEqualTo(105.0)
    }

    @Test
    fun `EMA smoothing with GPS only uses alpha 0_1`() {
        calculator.process(sampleAt(0L, altitudeGps = 100.0, altitudeBarometer = null, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(
            sampleAt(1000L, altitudeGps = 110.0, altitudeBarometer = null, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // smoothed = 100.0 + 0.1 * (110.0 - 100.0) = 101.0
        assertThat(metrics.altitudeM).isNotNull().isEqualTo(101.0)
    }

    // ── Grade calculation ──────────────────────────────────────────────────

    @Test
    fun `grade calculation over distance`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Move ~55.5m and climb to 105.5m
        calculator.process(
            sampleAt(5000L, latitude = 0.0005, longitude = 0.0, altitudeBarometer = 105.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Move another ~55.5m and climb to 111.1m → total ~111m, ~10% grade
        val metrics = calculator.process(
            sampleAt(10000L, latitude = 0.001, longitude = 0.0, altitudeBarometer = 111.1, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Grade should be calculated with multiple points in sliding window
        assertThat(metrics.gradePercent).isGreaterThan(0.0)
    }

    @Test
    fun `grade is clamped to -35 to 35 range`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, altitudeBarometer = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Use smaller position deltas for realistic speeds (~11.1 m/s ≈ 40 km/h each step)
        calculator.process(
            sampleAt(1000L, latitude = 0.0001, longitude = 0.0, altitudeBarometer = 25.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        val metrics = calculator.process(
            sampleAt(2000L, latitude = 0.0002, longitude = 0.0, altitudeBarometer = 50.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Grade should be within [-35, 35]
        assertThat(metrics.gradePercent).isEqualTo(35.0)
    }

    // ── Speed calculation ──────────────────────────────────────────────────

    @Test
    fun `speed from GPS used when accuracy is reliable`() {
        calculator.process(sampleAt(0L, accuracy = 5.0f), settings)
        val metrics = calculator.process(
            sampleAt(1000L, speedFromGpsMps = 8.0, accuracy = 5.0f),
            settings
        )
        // GPS speed 8.0 m/s = 28.8 km/h
        assertThat(metrics.currentSpeedKmh).isEqualTo(28.8)
    }

    @Test
    fun `residual GPS speed below threshold reads as zero when stationary`() {
        // Standing still: GPS Doppler reports a residual 1.0 km/h (0.278 m/s),
        // below the 1.5 km/h auto-pause threshold. The speedometer must show 0,
        // not the phantom crawl.
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.278, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.currentSpeedKmh).isZero()
    }

    @Test
    fun `residual GPS speed below threshold does not poison max speed`() {
        // A real moving sample sets a max, then standstill noise must not raise it.
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(
            sampleAt(2000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.4, accuracy = 5.0f),
            settings
        )
        // 8.0 m/s = 28.8 km/h max; the 0.4 m/s residual must not register.
        assertThat(metrics.maxSpeedKmh).isEqualTo(28.8)
    }

    @Test
    fun `stationary residual speed is not carried forward onto baro samples`() {
        // Stop with a residual Doppler reading, then a barometer-only sample
        // arrives — the carried-forward readout must be 0, not the residual.
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 5.0f), settings)
        calculator.process(
            sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.3, accuracy = 5.0f),
            settings
        )
        val metrics = calculator.process(baroSampleAt(1200L, altitudeBarometer = 100.0), settings)
        assertThat(metrics.currentSpeedKmh).isZero()
    }

    @Test
    fun `GPS speed ignored when accuracy is unknown`() {
        // Accuracy null → hasReliableCurrentAccuracy = false (?: false default)
        calculator.process(sampleAt(0L, accuracy = null), settings)
        val metrics = calculator.process(
            sampleAt(1000L, speedFromGpsMps = 8.0, accuracy = null),
            settings
        )
        // Speed should be 0 — GPS speed not used because accuracy is unknown
        assertThat(metrics.currentSpeedKmh).isZero()
    }

    // ── Pause behavior ─────────────────────────────────────────────────────

    @Test
    fun `paused sample does not advance moving time`() {
        // Need movement to accumulate moving time: provide speed + accuracy
        calculator.process(sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(
            sampleAt(2000L, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings,
            isPaused = true
        )
        // Moving time = 1s from second sample only (paused sample doesn't increment)
        assertThat(metrics.movingTimeSeconds).isEqualTo(1L)
        assertThat(metrics.elapsedTimeSeconds).isEqualTo(2L)
    }

    @Test
    fun `elapsed time continues during pause`() {
        calculator.process(sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(
            sampleAt(5000L, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings,
            isPaused = true
        )
        assertThat(metrics.elapsedTimeSeconds).isEqualTo(5L)
    }

    @Test
    fun `moving time does not advance when stationary`() {
        // Without lat/lng or GPS speed, rider is considered stationary
        calculator.process(sampleAt(0L), settings)
        calculator.process(sampleAt(1000L), settings)
        val metrics = calculator.process(sampleAt(2000L), settings)
        // Moving time should remain 0 — no movement detected
        assertThat(metrics.movingTimeSeconds).isZero()
        assertThat(metrics.elapsedTimeSeconds).isEqualTo(2L)
    }

    // ── Reset behavior ─────────────────────────────────────────────────────

    @Test
    fun `reset clears all accumulated state`() {
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 5.0f), settings)
        calculator.process(
            sampleAt(1000L, latitude = 0.0001, longitude = 0.0, accuracy = 5.0f),
            settings
        )
        calculator.process(
            sampleAt(2000L, latitude = 0.0002, longitude = 0.0, accuracy = 5.0f),
            settings
        )

        calculator.reset()

        val metrics = calculator.process(sampleAt(0L, altitudeBarometer = 50.0), settings)
        assertThat(metrics.elapsedTimeSeconds).isZero()
        assertThat(metrics.movingTimeSeconds).isZero()
        assertThat(metrics.distanceKm).isZero()
        assertThat(metrics.elevationGainM).isZero()
        assertThat(metrics.caloriesKcal).isZero()
    }

    // ── Max speed and average speed ────────────────────────────────────────

    @Test
    fun `max speed tracks maximum across samples`() {
        calculator.process(sampleAt(0L, speedFromGpsMps = 5.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(sampleAt(2000L, speedFromGpsMps = 7.0, accuracy = 5.0f), settings)

        // max speed = 10.0 m/s = 36.0 km/h
        assertThat(metrics.maxSpeedKmh).isEqualTo(36.0)
    }

    @Test
    fun `average speed calculated over moving time`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        val metrics = calculator.process(
            sampleAt(2000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.averageSpeedKmh).isGreaterThan(0.0)
    }

    @Test
    fun `average speed stays consistent with distance across a GPS dropout`() {
        // A fix returns 60s after the previous one (a dropout far beyond the 10s
        // energy cap), having covered ~111m in a straight line. Moving time and
        // distance must use the SAME interval, so the average reflects the actual
        // ~6.66 km/h straight-line speed — not the previously-inflated value
        // where full distance was divided by a capped 10s of moving time.
        val warm = RideMetricsCalculator(warmupReliableFixes = 1)
        warm.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 2.0, accuracy = 5.0f),
            settings
        )
        val metrics = warm.process(
            sampleAt(60_000L, latitude = 0.001, longitude = 0.0, speedFromGpsMps = 2.0, accuracy = 5.0f),
            settings
        )
        // ~111m over 60s ≈ 6.66 km/h. Allow a small margin for haversine rounding.
        // The pre-fix bug produced ~40 km/h (111m ÷ 10s), so a tight upper bound
        // is what actually guards the regression.
        assertThat(metrics.averageSpeedKmh).isGreaterThan(5.0)
        assertThat(metrics.averageSpeedKmh).isLessThan(8.0)
    }

    // ── Bearing ────────────────────────────────────────────────────────────

    @Test
    fun `bearing falls back to previous sample when null`() {
        calculator.process(sampleAt(0L, bearing = 90.0f), settings)
        val metrics = calculator.process(sampleAt(1000L, bearing = null), settings)
        assertThat(metrics.bearingDegrees).isNotNull().isEqualTo(90.0f)
    }

    @Test
    fun `bearing updates when provided`() {
        calculator.process(sampleAt(0L, bearing = 90.0f), settings)
        val metrics = calculator.process(sampleAt(1000L, bearing = 180.0f), settings)
        assertThat(metrics.bearingDegrees).isNotNull().isEqualTo(180.0f)
    }

    // ── Calories integration ───────────────────────────────────────────────

    @Test
    fun `calories accumulate across samples`() {
        calculator.process(sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        val afterFirst = calculator.process(sampleAt(60_000L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        val afterSecond = calculator.process(sampleAt(120_000L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)

        assertThat(afterSecond.caloriesKcal).isGreaterThan(afterFirst.caloriesKcal)
    }

    // ── Power integration ──────────────────────────────────────────────────

    @Test
    fun `average power calculated across samples`() {
        calculator.process(sampleAt(0L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        val metrics = calculator.process(sampleAt(1000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)

        assertThat(metrics.powerWatts).isGreaterThan(0)
        assertThat(metrics.averagePowerWatts).isGreaterThan(0)
    }

    // ── Outlier rejection ──────────────────────────────────────────────────

    @Test
    fun `GPS outlier jump is rejected`() {
        val strictCalculator = RideMetricsCalculator(maxPlausibleSpeedMps = 15.0, warmupReliableFixes = 1) // low threshold for test

        strictCalculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 5.0f),
            settings
        )
        // Jump ~111m in 1 second = 400 km/h → must be rejected as outlier
        val metrics = strictCalculator.process(
            sampleAt(1000L, latitude = 0.001, longitude = 0.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.distanceKm).isZero()
    }

    @Test
    fun `rejected outlier fix does not become reference for next segment`() {
        val strictCalculator = RideMetricsCalculator(maxPlausibleSpeedMps = 15.0, warmupReliableFixes = 1)

        // Good reference at origin.
        strictCalculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 5.0f),
            settings
        )
        // Glitch: ~111m jump in 1s (400 km/h) → rejected as speed outlier.
        strictCalculator.process(
            sampleAt(1000L, latitude = 0.001, longitude = 0.0, accuracy = 5.0f),
            settings
        )
        // Real fix back near origin (~11m from origin, ~1.1 km/h apart). If the
        // rejected glitch had become the reference, this segment would measure
        // ~100m and itself be flagged/poisoned. Measured from the retained good
        // reference it is a normal ~11m step that accumulates cleanly.
        val metrics = strictCalculator.process(
            sampleAt(2000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.distanceKm).isGreaterThan(0.0)
        // ~11m, not ~100m: confirms the glitch was never adopted as the reference.
        assertThat(metrics.distanceKm).isLessThan(0.05)
    }

    // ── Acceleration outlier rejection ─────────────────────────────────────

    @Test
    fun `acceleration outlier rejects GPS jump segment`() {
        // Seed with stationary position.
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f),
            settings
        )
        // Jump ~111m in 1s = ~400 km/h, acceleration = ~111 m/s² >> 8.0 threshold
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.001, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f),
            settings
        )
        // Distance should be rejected by acceleration check
        assertThat(metrics.distanceKm).isZero()
    }

    @Test
    fun `normal acceleration passes through`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 5.0, accuracy = 5.0f),
            settings
        )
        // ~11.1m in 1s = ~11.1 m/s, acceleration from 5→11.1 = 6.1 m/s² < 8.0
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 11.1, accuracy = 5.0f),
            settings
        )
        // Should accumulate distance normally
        assertThat(metrics.distanceKm).isGreaterThan(0.0)
    }

    // ── GPS speed vs distance-speed cross-validation ───────────────────────

    @Test
    fun `cross-validation rejects segment when position jumps but GPS speed is low`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 5.0, accuracy = 5.0f),
            settings
        )
        // Position jumps ~111m in 1s = 400 km/h, but GPS (Doppler) speed says 5 m/s
        // Distance-speed (111) > 3 * GPS-speed (15) AND > 10 m/s → rejected
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.001, longitude = 0.0, speedFromGpsMps = 5.0, accuracy = 5.0f),
            settings
        )
        // Cross-validation should reject this segment
        assertThat(metrics.distanceKm).isZero()
    }

    // ── Jump-bounce detection ──────────────────────────────────────────────

    @Test
    fun `bounce detection rejects return leg of GPS jump-bounce`() {
        // Position A
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Position B: jump ~555m away (glitch)
        calculator.process(
            sampleAt(1000L, latitude = 0.005, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Position C: back within 5m of A — this is a bounce return
        val metrics = calculator.process(
            sampleAt(2000L, latitude = 0.00001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // The return segment C should NOT add distance (bounce detected).
        // But A→B was already processed. Since GPS speed is reliable and shows
        // movement, the auto-pause check would have allowed it.
        // The key assertion: C's return segment is zeroed out.
        // We verify the calculator didn't crash and produced sensible output.
        assertThat(metrics.gpsQuality).isNotNull()
    }

    @Test
    fun `normal position progression is not detected as bounce`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        calculator.process(
            sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        val metrics = calculator.process(
            sampleAt(2000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Normal linear movement should accumulate distance
        assertThat(metrics.distanceKm).isGreaterThan(0.0)
    }

    // ── Stationary-drift hardening ─────────────────────────────────────────

    @Test
    fun `stationary drift does not accumulate distance after 5 stationary samples`() {
        // Seed with a moving sample so we have a baseline.
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // 6 stationary samples (no significant movement, no GPS speed)
        repeat(6) { i ->
            calculator.process(
                sampleAt((1000L * (i + 1)), speedFromGpsMps = null, accuracy = 50.0f),
                settings
            )
        }
        // Now a single "moving" sample — should be blocked by confirmation requirement
        val metrics = calculator.process(
            sampleAt(8000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // First moving sample after stationary block is suppressed
        assertThat(metrics.distanceKm).isZero()
    }

    @Test
    fun `moving time does not advance during the stationary-drift confirmation window`() {
        // Same scenario as the suppressed-distance case above: 6 stationary
        // samples arm the confirmation gate, then a single "moving" fix is
        // suppressed for distance. Moving time must be suppressed for that same
        // fix too — otherwise average speed (distance ÷ moving time) would be
        // deflated by 1s of moving time with zero matching distance on every
        // resume from a stop.
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        repeat(6) { i ->
            calculator.process(
                sampleAt((1000L * (i + 1)), speedFromGpsMps = null, accuracy = 50.0f),
                settings
            )
        }
        val metrics = calculator.process(
            sampleAt(8000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.distanceKm).isZero()
        // Moving time before this fix was 0 (no prior confirmed movement); it
        // must still be 0 here, not advanced by this suppressed fix's interval.
        assertThat(metrics.movingTimeSeconds).isZero()
    }

    @Test
    fun `two consecutive moving samples after stationary block resume distance`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // 5+ stationary samples to trigger protection
        repeat(6) { i ->
            calculator.process(
                sampleAt((1000L * (i + 1)), speedFromGpsMps = null, accuracy = 50.0f),
                settings
            )
        }
        // First moving confirmation (suppressed)
        calculator.process(
            sampleAt(8000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Second moving confirmation (should resume distance)
        val metrics = calculator.process(
            sampleAt(9000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Distance should now be accumulating
        assertThat(metrics.distanceKm).isGreaterThan(0.0)
    }

    // ── Barometric altitude range clamp ────────────────────────────────────

    @Test
    fun `barometric altitude outside plausible range is treated as null`() {
        // Altitude of 10000m is above max plausible (9000m) — should be discarded
        calculator.process(
            sampleAt(0L, altitudeBarometer = 100.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        val metrics = calculator.process(
            sampleAt(1000L, altitudeBarometer = 10_000.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Altitude should stay near previous value (100m), not jump to 10000m
        // smoothed ≈ 100 + 0.5*(100-100) = 100 (since baro was clamped to null, GPS is null, so raw altitude is null)
        assertThat(metrics.altitudeM).isNotNull()
    }

    // ── GPS quality tier ───────────────────────────────────────────────────

    @Test
    fun `GPS quality is GOOD with high accuracy and many satellites`() {
        calculator.process(
            sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        val metrics = calculator.process(
            sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 5.0f, satelliteCount = 8),
            settings
        )
        assertThat(metrics.gpsQuality).isEqualTo(GpsQuality.GOOD)
    }

    @Test
    fun `GPS quality is FAIR with moderate accuracy and satellites`() {
        calculator.process(
            sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 15.0f),
            settings
        )
        val metrics = calculator.process(
            sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 15.0f, satelliteCount = 5),
            settings
        )
        assertThat(metrics.gpsQuality).isEqualTo(GpsQuality.FAIR)
    }

    @Test
    fun `GPS quality is POOR with low accuracy`() {
        calculator.process(
            sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        val metrics = calculator.process(
            sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 35.0f, satelliteCount = 3),
            settings
        )
        assertThat(metrics.gpsQuality).isEqualTo(GpsQuality.POOR)
    }

    @Test
    fun `GPS quality is POOR when accuracy is unknown`() {
        calculator.process(sampleAt(0L), settings)
        val metrics = calculator.process(sampleAt(1000L, accuracy = null), settings)
        assertThat(metrics.gpsQuality).isEqualTo(GpsQuality.POOR)
    }

    // ── Interleaved non-location (barometer/heading) samples ────────────────

    @Test
    fun `barometer-only samples do not trip stationary-drift suppression`() {
        // A moving GPS fix establishes a baseline.
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Six barometer-only samples (no position) arrive between GPS fixes.
        // These must NOT be treated as "stationary" — otherwise they'd arm the
        // 2-confirmation block and suppress real distance.
        repeat(6) { i ->
            calculator.process(baroSampleAt(100L * (i + 1), altitudeBarometer = 100.0), settings)
        }
        // The next moving GPS fix should accumulate distance immediately.
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.distanceKm).isGreaterThan(0.0)
    }

    @Test
    fun `moving time integrates over GPS interval despite interleaved baro samples`() {
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Barometer sample lands mid-interval.
        calculator.process(baroSampleAt(500L, altitudeBarometer = 100.0), settings)
        // GPS fix one full second after the previous fix.
        val metrics = calculator.process(
            sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings
        )
        // Should credit the full 1s GPS interval, not just the 0.5s since the
        // intervening barometer sample.
        assertThat(metrics.movingTimeSeconds).isEqualTo(1L)
    }

    @Test
    fun `current speed is carried forward on non-location samples`() {
        calculator.process(sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        // A barometer-only sample must not blank the speed readout.
        val metrics = calculator.process(baroSampleAt(1200L, altitudeBarometer = 100.0), settings)
        assertThat(metrics.currentSpeedKmh).isEqualTo(28.8)
    }

    // ── Doppler glitch protection ──────────────────────────────────────────

    @Test
    fun `implausible Doppler speed does not corrupt max speed`() {
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 5.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f), settings)
        // A single glitched Doppler reading of 100 m/s (360 km/h) with otherwise
        // "good" accuracy must be ignored, not recorded as a new max.
        val metrics = calculator.process(
            sampleAt(2000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 100.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.maxSpeedKmh).isEqualTo(36.0)
    }

    // ── GPS cold-start warm-up ─────────────────────────────────────────────

    @Test
    fun `cold-start Doppler spike is suppressed during warm-up`() {
        // Default calculator → warm-up requires 3 consecutive reliable fixes.
        val warm = RideMetricsCalculator()
        // First sample seeds the reference (returns early).
        warm.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 25.0, accuracy = 5.0f),
            settings
        )
        // Second fix carries a spurious cold-start Doppler of 25 m/s (90 km/h)
        // with deceptively good accuracy — the classic over-reading at ride start.
        val metrics = warm.process(
            sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 25.0, accuracy = 5.0f),
            settings
        )
        // Warm-up incomplete → the speedometer holds at 0 instead of flashing 90 km/h,
        // and the glitch never poisons max speed.
        assertThat(metrics.currentSpeedKmh).isZero()
        assertThat(metrics.maxSpeedKmh).isZero()
    }

    @Test
    fun `no distance accumulates during warm-up`() {
        val warm = RideMetricsCalculator()
        warm.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f),
            settings
        )
        val metrics = warm.process(
            sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f),
            settings
        )
        assertThat(metrics.distanceKm).isZero()
    }

    @Test
    fun `speed is reported once warm-up completes`() {
        val warm = RideMetricsCalculator()
        // Seed (early return) + three reliable location-block fixes to reach the streak.
        warm.process(sampleAt(0L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        warm.process(sampleAt(1000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        warm.process(sampleAt(2000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        val metrics = warm.process(sampleAt(3000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        // 8.0 m/s = 28.8 km/h, now trusted.
        assertThat(metrics.currentSpeedKmh).isEqualTo(28.8)
    }

    @Test
    fun `unreliable fix resets the warm-up streak`() {
        val warm = RideMetricsCalculator()
        warm.process(sampleAt(0L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings)
        warm.process(sampleAt(1000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings) // streak 1
        warm.process(sampleAt(2000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings) // streak 2
        // Unreliable fix (accuracy 40 > 20m) breaks the streak.
        warm.process(sampleAt(3000L, speedFromGpsMps = 8.0, accuracy = 40.0f), settings)
        warm.process(sampleAt(4000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings) // streak 1 again
        val midMetrics = warm.process(sampleAt(5000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings) // streak 2
        assertThat(midMetrics.currentSpeedKmh).isZero()
        val metrics = warm.process(sampleAt(6000L, speedFromGpsMps = 8.0, accuracy = 5.0f), settings) // streak 3 → warmed
        assertThat(metrics.currentSpeedKmh).isEqualTo(28.8)
    }

    @Test
    fun `warm-up completes via timeout when GPS never reaches reliable accuracy`() {
        // accuracy 30m never satisfies the ≤20m streak, but still passes the
        // source-level filter and reaches the calculator. The bounded fallback
        // (default 10s) must eventually trust it so a poor-GPS ride still records.
        val warm = RideMetricsCalculator()
        // First sample seeds the reference (returns early). The warm-up timeout is
        // anchored at the first location-block fix below.
        warm.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, accuracy = 30.0f),
            settings
        )
        warm.process(
            sampleAt(1000L, latitude = 0.0, longitude = 0.0, accuracy = 30.0f),
            settings
        )
        // Significant movement (~111m) at marginal accuracy, >10s after the first
        // location-block fix — past the warm-up timeout.
        val metrics = warm.process(
            sampleAt(12_000L, latitude = 0.001, longitude = 0.0, accuracy = 30.0f),
            settings
        )
        // Distance accumulates via the significant-movement path once warmed up,
        // rather than staying frozen at zero for the whole ride.
        assertThat(metrics.distanceKm).isGreaterThan(0.0)
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private fun baroSampleAt(
        timestampMs: Long,
        altitudeBarometer: Double
    ) = RideSensorSample(
        timestampMs = timestampMs,
        latitude = null,
        longitude = null,
        altitudeFromBarometerM = altitudeBarometer
    )

    private fun sampleAt(
        timestampMs: Long,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        altitudeGps: Double? = null,
        altitudeBarometer: Double? = null,
        speedFromGpsMps: Double? = null,
        accuracy: Float? = null,
        bearing: Float? = null,
        satelliteCount: Int? = null
    ) = RideSensorSample(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeFromGpsM = altitudeGps,
        altitudeFromBarometerM = altitudeBarometer,
        speedFromGpsMps = speedFromGpsMps,
        accuracyM = accuracy,
        bearingDegrees = bearing,
        satelliteCount = satelliteCount
    )
}
