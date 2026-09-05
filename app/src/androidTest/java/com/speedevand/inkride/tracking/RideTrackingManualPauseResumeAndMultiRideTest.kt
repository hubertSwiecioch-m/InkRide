package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import com.speedevand.inkride.dashboard.presentation.DashboardConstants
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.tracking.support.dashboardString
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

class RideTrackingManualPauseResumeAndMultiRideTest : RideTrackingE2ETestBase() {
    @Test
    fun manualPauseFreezesTimeAndAResetRideStartsClean() {
        // --- First ride: start, move, pause, resume, move more, stop.
        startRideAndSettle()
        feedMovingSteps(count = 5)

        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) {
            it == dashboardString(R.string.dashboard_status_recording)
        }
        val movingTimeBeforePause = composeTestRule.textOf(DashboardTestTags.METRIC_MOVING_TIME)

        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) {
            it == dashboardString(R.string.dashboard_status_paused)
        }

        // Feed a couple more samples while paused: RideTracker must not
        // advance moving time for them.
        feedMovingSteps(count = 2)
        Thread.sleep(500L)
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_MOVING_TIME))
            .isEqualTo(movingTimeBeforePause)

        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) {
            it == dashboardString(R.string.dashboard_status_recording)
        }
        feedMovingSteps(count = 5)

        composeTestRule.waitUntilTagText(DashboardTestTags.METRIC_MOVING_TIME) {
            it != movingTimeBeforePause
        }

        val firstRideDistance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(firstRideDistance).isGreaterThan(0.0)

        composeTestRule.onNodeWithTag(DashboardTestTags.STOP_RESET_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) {
            it == dashboardString(R.string.dashboard_status_ready)
        }
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE))
            .isEqualTo(DashboardConstants.DISTANCE_ZERO)

        // --- Second ride: starting again must not carry over the first
        // ride's distance/time.
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) {
            it == dashboardString(R.string.dashboard_status_recording)
        }
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE))
            .isEqualTo(DashboardConstants.DISTANCE_ZERO)

        // 5 steps, not 3: RideMetricsCalculator's GPS cold-start warm-up
        // requires 3 consecutive reliable fixes before it trusts movement
        // data at all, so distance can still legitimately read 0.00 right
        // at that boundary — verified on-device. Every other absolute
        // distance/speed check in this suite already clears the gate with
        // margin; this one didn't.
        feedMovingSteps(count = 5)
        val secondRideDistance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(secondRideDistance).isGreaterThan(0.0)
        assertThat(secondRideDistance).isLessThan(firstRideDistance)
    }
}
