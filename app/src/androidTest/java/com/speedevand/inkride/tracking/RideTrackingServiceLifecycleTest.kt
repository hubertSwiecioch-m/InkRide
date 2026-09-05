package com.speedevand.inkride.tracking

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.service.TrackingService
import org.junit.Test

/**
 * Starting a ride must start `TrackingService` as a foreground service (it
 * keeps the process alive while the app is backgrounded/screen is off);
 * stopping the ride must tear it down. This is real-device-only behavior —
 * no JVM test can exercise the actual Android Service lifecycle.
 */
class RideTrackingServiceLifecycleTest : RideTrackingE2ETestBase() {
    @Test
    fun startingARideStartsTheForegroundServiceAndStoppingTearsItDown() {
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { !isTrackingServiceRunning() }
        assertThat(isTrackingServiceRunning()).isFalse()

        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { isTrackingServiceRunning() }
        assertThat(isTrackingServiceRunning()).isTrue()

        composeTestRule.onNodeWithTag(DashboardTestTags.STOP_RESET_BUTTON).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { !isTrackingServiceRunning() }
        assertThat(isTrackingServiceRunning()).isFalse()
    }

    private fun isTrackingServiceRunning(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TrackingService::class.java.name
        }
    }
}
