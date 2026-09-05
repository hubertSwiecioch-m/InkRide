package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEqualTo
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

/**
 * RideMetricsCalculator.computeGpsQuality: <=10m accuracy + >=6 satellites is
 * GOOD; <=20m (any satellite count) or <=30m with >=4 satellites is FAIR;
 * otherwise POOR.
 */
class RideTrackingGpsQualityTest : RideTrackingE2ETestBase() {
    @Test
    fun accuracyAndSatelliteCountDriveGpsQualityAndBarometerCoversDropouts() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        // Warm up + a few good fixes: GOOD.
        for (step in 1..3) {
            fakeSensorSource.emit(
                RideSamples.movingSample(
                    stepIndex = step,
                    nowMs = System.currentTimeMillis(),
                    accuracyM = 5f,
                    satelliteCount = 8,
                ),
            )
            Thread.sleep(1_000L)
        }
        composeTestRule.waitUntilTagText(DashboardTestTags.GPS_QUALITY) { it.contains("Good") }

        // Degrade to FAIR (15m accuracy).
        for (step in 4..6) {
            fakeSensorSource.emit(
                RideSamples.movingSample(
                    stepIndex = step,
                    nowMs = System.currentTimeMillis(),
                    accuracyM = 15f,
                    satelliteCount = 5,
                ),
            )
            Thread.sleep(1_000L)
        }
        composeTestRule.waitUntilTagText(DashboardTestTags.GPS_QUALITY) { it.contains("Fair") }

        // Degrade further to POOR (60m accuracy, 1 satellite).
        for (step in 7..9) {
            fakeSensorSource.emit(
                RideSamples.movingSample(
                    stepIndex = step,
                    nowMs = System.currentTimeMillis(),
                    accuracyM = 60f,
                    satelliteCount = 1,
                ),
            )
            Thread.sleep(1_000L)
        }
        composeTestRule.waitUntilTagText(DashboardTestTags.GPS_QUALITY) { it.contains("Poor") }

        val distanceBeforeDropout = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        val altitudeBeforeDropout = composeTestRule.textOf(DashboardTestTags.METRIC_ALTITUDE)

        // Full GPS dropout: only the barometer keeps reporting. Altitude
        // must keep updating; distance must never go backwards.
        fakeSensorSource.emit(
            RideSamples.movingSample(
                stepIndex = 10,
                nowMs = System.currentTimeMillis(),
                includeGpsFix = false,
                altitudeM = 130.0,
            ),
        )
        Thread.sleep(1_000L)

        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_ALTITUDE)).isNotEqualTo(altitudeBeforeDropout)
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble())
            .isGreaterThanOrEqualTo(distanceBeforeDropout)
    }
}
