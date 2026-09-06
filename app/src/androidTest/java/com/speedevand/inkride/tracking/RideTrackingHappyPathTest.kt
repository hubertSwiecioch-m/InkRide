package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
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
        // Every step is fed at a constant 20 km/h, so once past cold-start
        // warm-up the displayed speed should sit close to that constant,
        // not just "some positive number".
        val speed = composeTestRule.textOf(DashboardTestTags.SPEED_VALUE).toDouble()
        assertThat(speed).isCloseTo(20.0, 5.0)

        val distance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(distance).isGreaterThan(0.0)

        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_MOVING_TIME))
            .isNotEqualTo(DashboardConstants.TIME_ZERO)

        // Average speed over a constant-20km/h ride should also converge
        // close to 20, allowing for the cold-start warm-up window diluting it.
        val avgSpeed = composeTestRule.textOf(DashboardTestTags.METRIC_AVG_SPEED).toDouble()
        assertThat(avgSpeed).isCloseTo(20.0, 8.0)

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

        // Every fed sample reports exactly 20 km/h, so the tracked maximum
        // should sit right at that constant value.
        val maxSpeed = composeTestRule.textOf(DashboardTestTags.METRIC_MAX_SPEED).toDouble()
        assertThat(maxSpeed).isCloseTo(20.0, 2.0)

        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_ALTITUDE)).isNotEqualTo("--")

        // 2m of climb per step over 15 steps -- up to 28m if every step
        // counts; the cold-start warm-up window may exclude the first couple,
        // so a wide-but-diagnostic band instead of an exact figure.
        val elevationGain = composeTestRule.textOf(DashboardTestTags.METRIC_ELEVATION_GAIN).toDouble()
        assertThat(elevationGain).isGreaterThan(15.0)
        assertThat(elevationGain).isLessThan(30.0)

        // Calories/power have no clean closed-form expected value here (they
        // depend on the MET/physics models covered by CaloriesEstimatorTest/
        // PowerEstimatorTest on the JVM), but a sane upper bound still catches
        // a badly wrong computation, not just a stuck-at-zero one.
        val calories = composeTestRule.textOf(DashboardTestTags.METRIC_CALORIES).toDouble()
        assertThat(calories).isGreaterThan(0.0)
        assertThat(calories).isLessThan(50.0)

        // The synthetic 2m/step climb at 20 km/h is a ~36% grade (2m rise over
        // ~5.6m of horizontal travel per second) -- a legitimately steep
        // climb, not a bug, so PowerEstimator's gravity term correctly
        // demands a high wattage here (empirically ~1700W for these
        // parameters). The bound below is a sanity ceiling against a
        // genuinely broken computation, not a realistic-cycling ceiling.
        val power = composeTestRule.textOf(DashboardTestTags.METRIC_POWER).toInt()
        assertThat(power).isGreaterThan(0)
        assertThat(power).isLessThan(3_000)

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

        // observeAll() orders by startTimestamp DESC, so the just-finished
        // ride is first. Its persisted distance must match what was actually
        // on screen at stop time (captured as `distance` above, page 0),
        // not just "a ride got added" -- a mapping bug could persist the
        // wrong number while still incrementing the row count.
        val persistedDistance = ridesAfterStop.first().distanceKm
        assertThat(persistedDistance).isCloseTo(distance, 0.01)
    }
}
