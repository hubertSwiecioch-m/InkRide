package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import kotlin.math.cos
import org.junit.jupiter.api.Test

class PositionKalmanFilterTest {
    @Test
    fun `first fix passes through unchanged with zero speed`() {
        val filter = PositionKalmanFilter()
        val result = filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        assertThat(result.latitude).isEqualTo(50.0)
        assertThat(result.longitude).isEqualTo(19.0)
        assertThat(result.speedMps).isEqualTo(0.0)
        assertThat(result.wasGated).isFalse()
    }

    @Test
    fun `a fix within the accuracy noise floor is blended in, not gated`() {
        val filter = PositionKalmanFilter()
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 1_000L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 2_000L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 3_000L)

        // ~5m north of the true (converged) point — within the reported
        // accuracy, not an outlier.
        val noisyLat = 50.0 + 5.0 / 111_320.0
        val result = filter.update(latitude = noisyLat, longitude = 19.0, accuracyM = 5.0f, timestampMs = 4_000L)

        assertThat(result.wasGated).isFalse()
        // Blended toward, but not all the way to, the noisy 5m offset —
        // mathematically guaranteed for any valid Kalman gain in (0, 1) when
        // the pre-update predicted position exactly matches the reference
        // point, as it does here after four identical prior fixes.
        val filteredOffsetM = (result.latitude - 50.0) * 111_320.0
        assertThat(filteredOffsetM).isGreaterThan(0.0)
        assertThat(filteredOffsetM).isLessThan(5.0)
    }

    @Test
    fun `a wildly displaced fix is gated and the filter dead-reckons instead`() {
        val filter = PositionKalmanFilter()
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 1_000L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 2_000L)

        // 200m north in one second — a GPS glitch, not real movement at rest.
        val wildLat = 50.0 + 200.0 / 111_320.0
        val result = filter.update(latitude = wildLat, longitude = 19.0, accuracyM = 5.0f, timestampMs = 3_000L)

        assertThat(result.wasGated).isTrue()
        // Dead-reckoned from ~zero velocity, so it stays far from the wild fix.
        val distanceFromWildFixM = (wildLat - result.latitude) * 111_320.0
        assertThat(distanceFromWildFixM).isGreaterThan(100.0)
    }

    @Test
    fun `velocity direction follows the rider through a turn from east to north`() {
        val filter = PositionKalmanFilter()
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(50.0))
        val metersPerDegLat = 111_320.0

        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)

        // Ride east at ~5 m/s for 10 seconds.
        lateinit var eastResult: FilteredPosition
        for (step in 1..10) {
            val lon = 19.0 + (step * 5.0) / metersPerDegLon
            eastResult = filter.update(latitude = 50.0, longitude = lon, accuracyM = 5.0f, timestampMs = step * 1_000L)
        }
        // Bearing near 90° (east); generous tolerance for filter convergence.
        assertThat(eastResult.bearingDegrees!!).isGreaterThan(45f)
        assertThat(eastResult.bearingDegrees!!).isLessThan(135f)

        // Turn: ride north at ~5 m/s for 10 more seconds from the current longitude.
        val turnLon = 19.0 + (10 * 5.0) / metersPerDegLon
        lateinit var northResult: FilteredPosition
        for (step in 1..10) {
            val lat = 50.0 + (step * 5.0) / metersPerDegLat
            northResult = filter.update(latitude = lat, longitude = turnLon, accuracyM = 5.0f, timestampMs = (10 + step) * 1_000L)
        }
        // Bearing near 0°/360° (north) after the turn.
        val bearing = northResult.bearingDegrees!!
        assertThat(bearing < 60f || bearing > 300f).isTrue()
    }

    @Test
    fun `reset clears state so the next fix passes through unchanged again`() {
        val filter = PositionKalmanFilter()
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        filter.update(latitude = 50.0001, longitude = 19.0001, accuracyM = 5.0f, timestampMs = 1_000L)
        filter.reset()

        val result = filter.update(latitude = 51.0, longitude = 20.0, accuracyM = 5.0f, timestampMs = 5_000L)
        assertThat(result.latitude).isEqualTo(51.0)
        assertThat(result.longitude).isEqualTo(20.0)
        assertThat(result.speedMps).isEqualTo(0.0)
    }
}
