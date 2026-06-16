package com.speedevand.inkride.history.presentation

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.history.RideRecord
import org.junit.jupiter.api.Test

class RideRecordMappingTest {

    private val sampleRide = RideRecord(
        id = 1L,
        startTimestamp = 0L,
        endTimestamp = 3661000L,
        distanceKm = 10.5,
        movingTimeSeconds = 1800L,
        elapsedTimeSeconds = 3661L,
        averageSpeedKmh = 21.0,
        maxSpeedKmh = 35.0,
        elevationGainM = 150.0,
        caloriesKcal = 300.0,
        averagePowerWatts = 120
    )

    @Test
    fun `toUi with metric units formats correctly`() {
        val ui = sampleRide.toUi(MeasurementUnits.METRIC)
        assertThat(ui.id).isEqualTo(1L)
    }

    @Test
    fun `toUi with imperial units converts speed and distance`() {
        val ui = sampleRide.toUi(MeasurementUnits.IMPERIAL)
        assertThat(ui.distanceKm).isEqualTo("6.52 mi")
    }

    @Test
    fun `toDetailUi with metric units formats all fields`() {
        val ui = sampleRide.toDetailUi(MeasurementUnits.METRIC)
        assertThat(ui.id).isEqualTo(1L)
        assertThat(ui.distanceKm).isEqualTo("10.50 km")
    }

    @Test
    fun `toDetailUi with imperial units converts all unit-dependent fields`() {
        val ui = sampleRide.toDetailUi(MeasurementUnits.IMPERIAL)
        assertThat(ui.distanceKm).isEqualTo("6.52 mi")
        assertThat(ui.elevationGainM).isEqualTo("492 ft")
    }

    @Test
    fun `toClock formats zero correctly`() {
        val zeroRide = sampleRide.copy(movingTimeSeconds = 0, elapsedTimeSeconds = 0)
        val ui = zeroRide.toUi()
        assertThat(ui.movingTime).isEqualTo("00:00:00")
    }

    @Test
    fun `toClock formats with hours`() {
        val ui = sampleRide.toUi()
        // 1800 seconds = 00:30:00
        assertThat(ui.movingTime).isEqualTo("00:30:00")
    }
}
