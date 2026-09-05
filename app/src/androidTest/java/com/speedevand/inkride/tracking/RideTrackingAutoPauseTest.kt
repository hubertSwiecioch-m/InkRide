package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
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
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        // Get above the auto-pause threshold first.
        repeat(3) { index ->
            fakeSensorSource.emit(
                RideSamples.movingSample(stepIndex = index + 1, nowMs = System.currentTimeMillis()),
            )
            Thread.sleep(1_000L)
        }
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR) { it == "RECORDING RIDE" }

        // Stop moving for longer than the 3s auto-pause delay.
        repeat(5) {
            fakeSensorSource.emit(RideSamples.stationarySample(nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR, timeoutMillis = 10_000L) {
            it == "AUTO-PAUSED"
        }

        // Move again, above the (higher) auto-resume threshold.
        repeat(3) { index ->
            fakeSensorSource.emit(
                RideSamples.movingSample(stepIndex = 100 + index, nowMs = System.currentTimeMillis()),
            )
            Thread.sleep(1_000L)
        }
        composeTestRule.waitUntilTagText(DashboardTestTags.STATUS_INDICATOR, timeoutMillis = 10_000L) {
            it == "RECORDING RIDE"
        }
    }
}
