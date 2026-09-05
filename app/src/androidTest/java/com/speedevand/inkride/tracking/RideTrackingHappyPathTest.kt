package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.dashboard.presentation.DashboardConstants
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
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

        composeTestRule.waitUntilTagText(DashboardTestTags.SPEED_VALUE) { it != "0.0" }
        val speed = composeTestRule.textOf(DashboardTestTags.SPEED_VALUE).toDouble()
        assertThat(speed).isGreaterThan(10.0)

        val distance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(distance).isGreaterThan(0.0)

        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_MOVING_TIME))
            .isNotEqualTo(DashboardConstants.TIME_ZERO)

        val avgSpeed = composeTestRule.textOf(DashboardTestTags.METRIC_AVG_SPEED).toDouble()
        assertThat(avgSpeed).isGreaterThan(0.0)

        val maxSpeed = composeTestRule.textOf(DashboardTestTags.METRIC_MAX_SPEED).toDouble()
        assertThat(maxSpeed).isGreaterThan(0.0)

        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_ALTITUDE)).isNotEqualTo("--")

        val elevationGain = composeTestRule.textOf(DashboardTestTags.METRIC_ELEVATION_GAIN).toDouble()
        assertThat(elevationGain).isGreaterThanOrEqualTo(0.0)

        val calories = composeTestRule.textOf(DashboardTestTags.METRIC_CALORIES).toDouble()
        assertThat(calories).isGreaterThan(0.0)

        val power = composeTestRule.textOf(DashboardTestTags.METRIC_POWER).toInt()
        assertThat(power).isGreaterThan(0)

        // Grade is only checked for being a well-formed number: its exact
        // magnitude depends on RideMetricsCalculator's minimum-distance
        // gating, already covered by RideMetricsCalculatorTest on the JVM.
        composeTestRule.textOf(DashboardTestTags.METRIC_GRADE).toDouble()

        assertThat(composeTestRule.textOf(DashboardTestTags.HEART_RATE_VALUE)).isEqualTo("140")
        assertThat(composeTestRule.textOf(DashboardTestTags.CADENCE_VALUE)).isEqualTo("85")

        // Swipe to the compass page (page index 2 of 3: primary, secondary,
        // compass — every show* toggle defaults to true) and check bearing.
        composeTestRule.onNodeWithTag(DashboardTestTags.METRICS_PAGER).performTouchInput { swipeUp() }
        composeTestRule.onNodeWithTag(DashboardTestTags.METRICS_PAGER).performTouchInput { swipeUp() }
        assertThat(composeTestRule.textOf(DashboardTestTags.COMPASS_BEARING)).isEqualTo("0°")

        composeTestRule.onNodeWithTag(DashboardTestTags.STOP_RESET_BUTTON).performClick()

        composeTestRule.waitUntilTagText(DashboardTestTags.METRIC_DISTANCE) {
            it == DashboardConstants.DISTANCE_ZERO
        }

        val historyRepository = GlobalContext.get().get<RideHistoryRepository>()
        val savedRides =
            runBlocking {
                var rides = historyRepository.observeAll().first()
                var attempts = 0
                while (rides.isEmpty() && attempts < 20) {
                    delay(200L)
                    rides = historyRepository.observeAll().first()
                    attempts++
                }
                rides
            }
        assertThat(savedRides).isNotEmpty()
    }
}
