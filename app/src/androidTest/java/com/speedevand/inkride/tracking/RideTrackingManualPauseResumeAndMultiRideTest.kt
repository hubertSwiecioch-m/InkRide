package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import com.speedevand.inkride.dashboard.presentation.DashboardConstants
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

class RideTrackingManualPauseResumeAndMultiRideTest : RideTrackingE2ETestBase() {
    @Test
    fun manualPauseFreezesTimeAndAResetRideStartsClean() {
        // --- First ride: start, move, pause, resume, move more, stop.
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)
        feedMovingSteps(startStep = 1, count = 5)

        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) { it == "RECORDING RIDE" }
        val movingTimeBeforePause = composeTestRule.textOf(DashboardTestTags.METRIC_MOVING_TIME)

        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) { it == "PAUSED" }

        // Feed a couple more samples while paused: RideTracker must not
        // advance moving time for them.
        feedMovingSteps(startStep = 6, count = 2)
        Thread.sleep(500L)
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_MOVING_TIME))
            .isEqualTo(movingTimeBeforePause)

        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) { it == "RECORDING RIDE" }
        feedMovingSteps(startStep = 8, count = 5)

        composeTestRule.waitUntilTagText(DashboardTestTags.METRIC_MOVING_TIME) {
            it != movingTimeBeforePause
        }

        val firstRideDistance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(firstRideDistance).isGreaterThan(0.0)

        composeTestRule.onNodeWithTag(DashboardTestTags.STOP_RESET_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) { it == "READY MODE" }
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE))
            .isEqualTo(DashboardConstants.DISTANCE_ZERO)

        // --- Second ride: starting again must not carry over the first
        // ride's distance/time.
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) { it == "RECORDING RIDE" }
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE))
            .isEqualTo(DashboardConstants.DISTANCE_ZERO)

        feedMovingSteps(startStep = 1, count = 3)
        val secondRideDistance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(secondRideDistance).isGreaterThan(0.0)
        assertThat(secondRideDistance).isNotEqualTo(firstRideDistance)
    }

    private fun feedMovingSteps(
        startStep: Int,
        count: Int,
    ) {
        repeat(count) { offset ->
            val stepIndex = startStep + offset
            fakeSensorSource.emit(
                RideSamples.movingSample(stepIndex = stepIndex, nowMs = System.currentTimeMillis()),
            )
            Thread.sleep(1_000L)
        }
    }
}
