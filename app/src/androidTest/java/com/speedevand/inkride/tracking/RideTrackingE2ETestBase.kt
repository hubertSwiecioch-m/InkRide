package com.speedevand.inkride.tracking

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.speedevand.inkride.MainActivity
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.fakes.FakeBleSensorDataSource
import com.speedevand.inkride.tracking.fakes.FakeRideSensorDataSource
import com.speedevand.inkride.tracking.service.TrackingService
import com.speedevand.inkride.tracking.support.RideSamples
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Base for instrumented ride-tracking tests. Swaps [RideSensorDataSource] and
 * [BleSensorDataSource] for controllable fakes (an emulator has no real
 * GPS/BLE hardware to drive), and re-registers the `RideTracker` single so a
 * fresh instance is built against them. `RideTracker` is process-scoped, so
 * without re-registering it a stale instance from a previous test class would
 * keep holding the *previous* test's fakes and internal ride state.
 *
 * The override happens in [setUpRideTracking], strictly before [MainActivity]
 * is launched — so the first `koinViewModel()` resolution inside the launched
 * activity (which resolves `RideTracker`) sees the fakes, not the production
 * Android sensor sources. This is also why this base class launches the
 * activity itself with [ActivityScenario] instead of using
 * `createAndroidComposeRule`, whose auto-launch would happen before `@Before`
 * runs.
 */
abstract class RideTrackingE2ETestBase {
    @get:Rule
    val permissionRule: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    @get:Rule
    val composeTestRule: ComposeTestRule = createEmptyComposeRule()

    val fakeSensorSource = FakeRideSensorDataSource()
    val fakeBleSource = FakeBleSensorDataSource()

    private val testModule: Module =
        module {
            single<RideSensorDataSource> { fakeSensorSource }
            single<BleSensorDataSource> { fakeBleSource }
            single { RideTracker(get(), get(), get(), get(), get(), get(), get()) }
        }

    private var scenario: ActivityScenario<MainActivity>? = null

    /**
     * Every metric-visibility toggle defaults to on in [UserSettings]; this
     * seeds a weight/age (required for calorie/power estimation) plus two
     * dummy paired-sensor addresses so the BLE "disconnected" banner and
     * HR/cadence readouts are exercised. Override in a subclass to seed
     * different settings (e.g. alert thresholds).
     */
    protected open fun seedSettings(): UserSettings =
        UserSettings(
            weightKg = 75,
            age = 30,
            pairedHrmAddress = "AA:BB:CC:DD:EE:01",
            pairedCadenceAddress = "AA:BB:CC:DD:EE:02",
        )

    @Before
    fun setUpRideTracking() {
        loadKoinModules(listOf(testModule))
        val userSettingsRepository = GlobalContext.get().get<UserSettingsRepository>()
        runBlocking { userSettingsRepository.save(seedSettings()) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDownRideTracking() {
        runCatching { GlobalContext.get().get<RideTracker>().stop() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startService(
            Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP),
        )
        scenario?.close()
        unloadKoinModules(listOf(testModule))
    }

    // Monotonically increasing across a single test's lifetime (a fresh
    // RideTrackingE2ETestBase instance per @Test), so every feedMovingSteps
    // call continues the same straight-line path RideSamples expects —
    // never re-using or skipping stepIndex values, which would either
    // replay an old position or teleport (RideSamples.kt's cross-validation
    // warning).
    private var stepCursor = 0

    protected fun startRideAndSettle() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        // Let RideTracker's settings collector pick up the seeded UserSettings
        // before the first sample is processed.
        Thread.sleep(300L)
    }

    protected fun feedMovingSteps(
        count: Int,
        speedKmh: Double = 20.0,
        accuracyM: Float = 5f,
        satelliteCount: Int = 8,
        bearingDegrees: Float = 0f,
        altitudeM: Double = 100.0,
        includeGpsFix: Boolean = true,
        stepIntervalMs: Long = 1_000L,
    ) {
        repeat(count) {
            stepCursor++
            fakeSensorSource.emit(
                RideSamples.movingSample(
                    stepIndex = stepCursor,
                    nowMs = System.currentTimeMillis(),
                    speedKmh = speedKmh,
                    accuracyM = accuracyM,
                    satelliteCount = satelliteCount,
                    bearingDegrees = bearingDegrees,
                    altitudeM = altitudeM,
                    includeGpsFix = includeGpsFix,
                ),
            )
            Thread.sleep(stepIntervalMs)
        }
    }
}
