package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.dashboard.presentation.DashboardConstants
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.swipeMetricsPagerToNextPage
import com.speedevand.inkride.tracking.support.swipeMetricsPagerToPreviousPage
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Drives a full ride through the real Compose UI, feeding synthetic GPS,
 * barometer, and BLE HR/cadence samples through the fakes wired in
 * [RideTrackingE2ETestBase], and asserts every in-ride measurement updates —
 * across both metric pager pages and the compass page — then stops the ride
 * and confirms it was persisted to ride history.
 */
class RideTrackingHappyPathTest : RideTrackingE2ETestBase() {
    @Test
    fun fullRideUpdatesAllMeasurementsAndPersistsToHistory() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        // Let RideTracker's settings collector pick up the seeded UserSettings
        // before the first sample is processed.
        Thread.sleep(300L)

        // 15 steps at 20 km/h, climbing 2m/step. RideMetricsCalculator
        // requires 3 consecutive reliable (<=20m accuracy) fixes before it
        // trusts movement data, so the first 3 of these matter as much as the
        // rest — see its "GPS cold-start warm-up" doc comment.
        repeat(15) { index ->
            val stepIndex = index + 1
            val sample =
                RideSamples.movingSample(
                    stepIndex = stepIndex,
                    nowMs = System.currentTimeMillis(),
                    speedKmh = 20.0,
                    altitudeM = 100.0 + stepIndex * 2.0,
                )
            fakeSensorSource.emit(sample)
            fakeBleSource.emit(
                BleSample(
                    timestampMs = System.currentTimeMillis(),
                    heartRateBpm = 140,
                    cadenceRpm = 85,
                    connected = true,
                    cadenceUpdatedAtMs = System.currentTimeMillis(),
                ),
            )
            Thread.sleep(1_000L)
        }

        // --- Page 0 (primary): speed, distance, moving time, avg speed, grade.
        composeTestRule.waitUntilTagText(DashboardTestTags.SPEED_VALUE) { it != "0.0" }
        val speed = composeTestRule.textOf(DashboardTestTags.SPEED_VALUE).toDouble()
        assertThat(speed).isGreaterThan(10.0)

        val distance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(distance).isGreaterThan(0.0)

        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_MOVING_TIME))
            .isNotEqualTo(DashboardConstants.TIME_ZERO)

        val avgSpeed = composeTestRule.textOf(DashboardTestTags.METRIC_AVG_SPEED).toDouble()
        assertThat(avgSpeed).isGreaterThan(0.0)

        // Grade is only checked for being a well-formed number: its exact
        // magnitude depends on RideMetricsCalculator's minimum-distance
        // gating, already covered by RideMetricsCalculatorTest on the JVM.
        composeTestRule.textOf(DashboardTestTags.METRIC_GRADE).toDouble()

        // HR/cadence live in InfoBar, outside the pager — always composed
        // regardless of page. InfoBar formats them with units/zone, so this
        // checks for the reading, not an isolated bare number.
        assertThat(composeTestRule.textOf(DashboardTestTags.HEART_RATE_VALUE)).contains("140")
        assertThat(composeTestRule.textOf(DashboardTestTags.CADENCE_VALUE)).contains("85")

        // --- Page 1 (secondary): max speed, elevation gain, calories, altitude, power.
        composeTestRule.swipeMetricsPagerToNextPage()

        val maxSpeed = composeTestRule.textOf(DashboardTestTags.METRIC_MAX_SPEED).toDouble()
        assertThat(maxSpeed).isGreaterThan(0.0)

        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_ALTITUDE)).isNotEqualTo("--")

        val elevationGain = composeTestRule.textOf(DashboardTestTags.METRIC_ELEVATION_GAIN).toDouble()
        assertThat(elevationGain).isGreaterThan(0.0)

        val calories = composeTestRule.textOf(DashboardTestTags.METRIC_CALORIES).toDouble()
        assertThat(calories).isGreaterThan(0.0)

        val power = composeTestRule.textOf(DashboardTestTags.METRIC_POWER).toInt()
        assertThat(power).isGreaterThan(0)

        // --- Page 2 (compass): bearing.
        composeTestRule.swipeMetricsPagerToNextPage()
        assertThat(composeTestRule.textOf(DashboardTestTags.COMPASS_BEARING)).isEqualTo("0°")

        // Back to page 0 before the post-stop reset check below.
        composeTestRule.swipeMetricsPagerToPreviousPage()
        composeTestRule.swipeMetricsPagerToPreviousPage()

        val historyRepository = GlobalContext.get().get<RideHistoryRepository>()
        val ridesBeforeStop = runBlocking { historyRepository.observeAll().first() }

        composeTestRule.onNodeWithTag(DashboardTestTags.STOP_RESET_BUTTON).performClick()

        composeTestRule.waitUntilTagText(DashboardTestTags.METRIC_DISTANCE) {
            it == DashboardConstants.DISTANCE_ZERO
        }

        val ridesAfterStop =
            runBlocking {
                var rides = historyRepository.observeAll().first()
                var attempts = 0
                while (rides.size <= ridesBeforeStop.size && attempts < 20) {
                    delay(200L)
                    rides = historyRepository.observeAll().first()
                    attempts++
                }
                rides
            }
        assertThat(ridesAfterStop.size).isGreaterThan(ridesBeforeStop.size)
    }
}
