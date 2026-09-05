package com.speedevand.inkride.tracking

import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.dashboardString
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

/**
 * RideTracker auto-pauses after the speed stays below 1.5 km/h for 3s, and
 * auto-resumes once it exceeds 2.5 km/h (see RideTracker's autoPauseSpeedKmh
 * / autoResumeSpeedKmh / autoPauseDelayMs defaults) — this test only checks
 * that the real, wall-clock-driven transition reaches the UI; the thresholds
 * themselves are covered by RideTrackerTest on the JVM.
 */
class RideTrackingAutoPauseTest : RideTrackingE2ETestBase() {
    @Test
    fun stoppingMovementAutoPausesAndMovingAgainAutoResumes() {
        startRideAndSettle()

        // Get above the auto-pause threshold first.
        feedMovingSteps(count = 3)
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) {
            it == dashboardString(R.string.dashboard_status_recording)
        }

        // Stop moving for longer than the 3s auto-pause delay. Stationary
        // samples don't advance the shared step cursor (position doesn't
        // change while stopped).
        repeat(5) {
            fakeSensorSource.emit(RideSamples.stationarySample(nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR, timeoutMillis = 10_000L) {
            it == dashboardString(R.string.dashboard_status_auto_paused)
        }

        // Move again, above the (higher) auto-resume threshold — continuing
        // the same straight-line path from where the last moving sample left
        // off, not teleporting.
        feedMovingSteps(count = 3)
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR, timeoutMillis = 10_000L) {
            it == dashboardString(R.string.dashboard_status_recording)
        }
    }
}
