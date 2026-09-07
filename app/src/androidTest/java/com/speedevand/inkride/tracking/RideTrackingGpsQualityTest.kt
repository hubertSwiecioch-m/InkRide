package com.speedevand.inkride.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.swipeMetricsPagerToNextPage
import com.speedevand.inkride.tracking.support.swipeMetricsPagerToPreviousPage
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
        startRideAndSettle()

        // Warm up + a few good fixes: GOOD.
        feedMovingSteps(count = 3, accuracyM = 5f, satelliteCount = 8)
        composeTestRule.waitUntilTagText(DashboardTestTags.GPS_QUALITY) { it.contains("Good") }

        // Degrade to FAIR (15m accuracy).
        feedMovingSteps(count = 3, accuracyM = 15f, satelliteCount = 5)
        composeTestRule.waitUntilTagText(DashboardTestTags.GPS_QUALITY) { it.contains("Fair") }

        // Degrade further to POOR (60m accuracy, 1 satellite).
        feedMovingSteps(count = 3, accuracyM = 60f, satelliteCount = 1)
        composeTestRule.waitUntilTagText(DashboardTestTags.GPS_QUALITY) { it.contains("Poor") }

        val distanceBeforeDropout = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()

        // Page 1 is speed/grade; page 2 (secondary) is where altitude lives.
        composeTestRule.swipeMetricsPagerToNextPage()
        composeTestRule.swipeMetricsPagerToNextPage()
        val altitudeBeforeDropout = composeTestRule.textOf(DashboardTestTags.METRIC_ALTITUDE).toDouble()

        // Full GPS dropout: only the barometer keeps reporting. Altitude
        // must move toward the new barometer reading; distance (GPS-derived)
        // must not move at all since no lat/lon fix arrives. The displayed
        // altitude is a GPS/barometer complementary filter, not a raw
        // passthrough (confirmed empirically: injecting 130.0 here after a
        // run of 100.0 readings produced 115, not 130), so this checks the
        // fusion converged *toward* the injected value rather than an exact
        // figure that depends on the filter's own time constant.
        fakeSensorSource.emit(
            RideSamples.movingSample(
                stepIndex = 10,
                nowMs = System.currentTimeMillis(),
                includeGpsFix = false,
                altitudeM = 130.0,
            ),
        )
        Thread.sleep(1_000L)

        val altitudeAfterDropout = composeTestRule.textOf(DashboardTestTags.METRIC_ALTITUDE).toDouble()
        assertThat(altitudeAfterDropout).isGreaterThan(altitudeBeforeDropout)
        assertThat(altitudeAfterDropout).isLessThanOrEqualTo(130.0)

        composeTestRule.swipeMetricsPagerToPreviousPage()
        composeTestRule.swipeMetricsPagerToPreviousPage()
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble())
            .isEqualTo(distanceBeforeDropout)
    }
}
