package com.speedevand.inkride.dashboard.presentation.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.RideMetrics
import org.junit.jupiter.api.Test

class RideMetricsUiTest {
    @Test
    fun `toRideMetricsUi with metric units preserves values`() {
        val metrics =
            RideMetrics(
                currentSpeedKmh = 25.5,
                distanceKm = 10.0,
                elevationGainM = 150.0,
                altitudeM = 200.0,
            )
        val ui = metrics.toRideMetricsUi(MeasurementUnits.METRIC)
        assertThat(ui.currentSpeedKmh).isEqualTo("25.5")
        assertThat(ui.speedUnit).isEqualTo("km/h")
        assertThat(ui.distanceUnit).isEqualTo("km")
        assertThat(ui.altitudeUnit).isEqualTo("m")
    }

    @Test
    fun `toRideMetricsUi with imperial units converts speeds`() {
        val metrics = RideMetrics(currentSpeedKmh = 25.5)
        val ui = metrics.toRideMetricsUi(MeasurementUnits.IMPERIAL)
        assertThat(ui.speedUnit).isEqualTo("mph")
        assertThat(ui.distanceUnit).isEqualTo("mi")
        assertThat(ui.altitudeUnit).isEqualTo("ft")
    }

    @Test
    fun `toRideMetricsUi with imperial units converts distances`() {
        val metrics = RideMetrics(distanceKm = 10.0)
        val ui = metrics.toRideMetricsUi(MeasurementUnits.IMPERIAL)
        // 10.0 * 0.621371 ≈ 6.21
        assertThat(ui.distanceKm).isEqualTo("6.21")
    }

    @Test
    fun `toRideMetricsUi with imperial units converts altitudes`() {
        val metrics = RideMetrics(altitudeM = 100.0, elevationGainM = 50.0)
        val ui = metrics.toRideMetricsUi(MeasurementUnits.IMPERIAL)
        // 100.0 * 3.28084 ≈ 328
        assertThat(ui.altitudeM).isEqualTo("328")
        // 50.0 * 3.28084 ≈ 164
        assertThat(ui.elevationGainM).isEqualTo("164")
    }

    @Test
    fun `Long toClock formats zero seconds`() {
        val metrics = RideMetrics(movingTimeSeconds = 0, elapsedTimeSeconds = 0)
        val ui = metrics.toRideMetricsUi()
        assertThat(ui.movingTime).isEqualTo("00:00:00")
        assertThat(ui.elapsedTime).isEqualTo("00:00:00")
    }

    @Test
    fun `Long toClock formats with hours`() {
        val metrics = RideMetrics(movingTimeSeconds = 3661, elapsedTimeSeconds = 7322)
        val ui = metrics.toRideMetricsUi()
        assertThat(ui.movingTime).isEqualTo("01:01:01")
        assertThat(ui.elapsedTime).isEqualTo("02:02:02")
    }

    @Test
    fun `null altitude renders as dash`() {
        val metrics = RideMetrics(altitudeM = null)
        val ui = metrics.toRideMetricsUi()
        assertThat(ui.altitudeM).isEqualTo("--")
    }

    @Test
    fun `null gps accuracy shows poor quality with dash`() {
        val metrics = RideMetrics(gpsAccuracyM = null)
        val ui = metrics.toRideMetricsUi()
        assertThat(ui.gpsAccuracyM).isEqualTo("Poor (--)")
    }
}
