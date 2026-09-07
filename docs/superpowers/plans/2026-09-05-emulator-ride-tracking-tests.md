# Emulator Ride-Tracking Instrumented Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a suite of instrumented Compose UI tests, run on an Android emulator, that drive the real ride-tracking flow end to end (real Koin graph, real Room persistence, real `TrackingService` foreground-service lifecycle) and assert every in-ride measurement plus the ride lifecycle (pause/resume, auto-pause, laps, goals, alerts, BLE sensor loss, GPS quality).

**Architecture:** Tests live in `app/src/androidTest/java/com/speedevand/inkride/tracking/` and launch the real `MainActivity`. `RideSensorDataSource` and `BleSensorDataSource` are swapped for test-controlled fakes via a Koin module override (`loadKoinModules`, applied before the Activity launches), plus a re-registered `RideTracker` single so each test gets a fresh instance built against its own fakes. Real `UserSettingsRepository`/`RideHistoryRepository`/Room stay wired as-is. A handful of `Modifier.testTag(...)` additions to Dashboard composables let tests assert on live values without depending on formatted/localized text.

**Tech Stack:** Kotlin, Jetpack Compose UI Test (`androidx.compose.ui:ui-test-junit4`), `androidx.test:core`/`androidx.test:rules` (`ActivityScenario`, `GrantPermissionRule`), Koin 4.2.1 (`loadKoinModules`/`unloadKoinModules`), AssertK, JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-05-emulator-ride-tracking-tests-design.md`

## Global Constraints

- Tests run via `./gradlew :app:connectedDebugAndroidTest` against a running emulator (API 26+, no Google Play image required — de-googled constraint).
- No CI wiring in this change (per spec's "Out of scope").
- Do not re-derive `RideTracker`/`RideMetricsCalculator` business-logic edge cases already covered by JVM unit tests in `:core:domain` — assertions in this suite check wiring/UI/persistence/service lifecycle, using generous tolerances for numeric metrics rather than exact arithmetic replication.
- `RideTracker` is a Koin `single` (process-scoped) — every test's Koin override must re-register it too (not just its dependencies), otherwise a stale instance from a previous test class leaks its fakes and internal state across tests in the same instrumentation process.
- All new/changed production code lives in `:feature:dashboard:presentation` (test tags only — no behavior change) and `app/build.gradle.kts` / `gradle/libs.versions.toml` (test dependencies only).

---

## Task 1: Add instrumented-test Gradle dependencies

**Files:**
- Modify: `gradle/libs.versions.toml:67`
- Modify: `app/build.gradle.kts:126-129`

**Interfaces:**
- Produces: `libs.androidx.test.rules` and `libs.androidx.test.core` version-catalog aliases, and `androidTestImplementation(libs.assertk)` on `:app`, consumed by every task from Task 2 onward.

- [ ] **Step 1: Add the `androidx-test-rules` catalog entry**

In `gradle/libs.versions.toml`, immediately after the existing `androidx-test-core` line:

```toml
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestCore" }
```

(`androidx.test:rules` ships in lockstep with `androidx.test:core` in the AndroidX Test release train, so reusing `androidxTestCore` as the version ref is correct.)

- [ ] **Step 2: Add the androidTest dependencies to `:app`**

In `app/build.gradle.kts`, the current tail of the `dependencies {}` block is:

```kotlin
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
```

Change it to:

```kotlin
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.assertk)
```

- [ ] **Step 3: Verify the project still syncs/compiles**

Run: `./gradlew :app:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL` (no test source files reference the new deps yet, but Gradle must resolve them).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "test: add androidx.test core/rules and assertk to :app androidTest

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 2: Dashboard test tags, ride-tracking test infrastructure, and the happy-path test

This is the first end-to-end slice: it adds every `testTag` the whole suite needs, the fakes/base-class/helpers every later test class reuses, and one full scenario test proving the wiring works. Later tasks only add new test classes on top of this infrastructure.

**Files:**
- Create: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardTestTags.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/MetricItem.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/MetricsPager.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/SpeedHero.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/Compass.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/InfoBar.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/StatusIndicator.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/DashboardActions.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/DashboardTopBar.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/LapGoalControls.kt`
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/FakeRideSensorDataSource.kt`
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/FakeBleSensorDataSource.kt`
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/support/RideSamples.kt`
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/support/ComposeTestHelpers.kt`
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingE2ETestBase.kt`
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingHappyPathTest.kt`

**Interfaces:**
- Produces: `DashboardTestTags` (all tag constants used by every later task), `FakeRideSensorDataSource.emit(RideSensorSample)`, `FakeBleSensorDataSource.emit(BleSample)`, `RideSamples.movingSample(stepIndex, nowMs, speedKmh, accuracyM, satelliteCount, bearingDegrees, altitudeM, includeGpsFix): RideSensorSample`, `RideSamples.stationarySample(nowMs, accuracyM, satelliteCount): RideSensorSample`, `ComposeTestRule.textOf(tag): String`, `ComposeTestRule.waitUntilTagText(tag, timeoutMillis, predicate)`, and the abstract base `RideTrackingE2ETestBase` with protected fields `composeTestRule`, `fakeSensorSource`, `fakeBleSource`, and `protected open fun seedSettings(): UserSettings`. All consumed by Tasks 3-9.

### Step 1: Add the test-tag constants

- [ ] Create `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardTestTags.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

/**
 * Stable identifiers for Compose UI tests. Applied directly to the leaf
 * text/interactive nodes that carry live ride data, so instrumented tests can
 * assert on values without depending on formatted/localized text.
 */
object DashboardTestTags {
    const val METRICS_PAGER = "dashboard_metrics_pager"
    const val SPEED_VALUE = "dashboard_speed_value"
    const val STATUS_INDICATOR = "dashboard_status_indicator"
    const val START_PAUSE_BUTTON = "dashboard_start_pause_button"
    const val STOP_RESET_BUTTON = "dashboard_stop_reset_button"
    const val COMPASS_BEARING = "dashboard_compass_bearing"
    const val HEART_RATE_VALUE = "dashboard_heart_rate_value"
    const val CADENCE_VALUE = "dashboard_cadence_value"
    const val SENSOR_DISCONNECTED = "dashboard_sensor_disconnected"
    const val GPS_QUALITY = "dashboard_gps_quality"
    const val GOAL_STATUS = "dashboard_goal_status"
    const val LAST_LAP_STATUS = "dashboard_last_lap_status"
    const val RECORD_LAP_BUTTON = "dashboard_record_lap_button"
    const val GOAL_BUTTON = "dashboard_goal_button"
    const val GOAL_VALUE_FIELD = "dashboard_goal_value_field"
    const val GOAL_SET_BUTTON = "dashboard_goal_set_button"

    const val METRIC_DISTANCE = "dashboard_metric_value_distance"
    const val METRIC_MOVING_TIME = "dashboard_metric_value_moving_time"
    const val METRIC_AVG_SPEED = "dashboard_metric_value_avg_speed"
    const val METRIC_GRADE = "dashboard_metric_value_grade"
    const val METRIC_MAX_SPEED = "dashboard_metric_value_max_speed"
    const val METRIC_ELEVATION_GAIN = "dashboard_metric_value_elevation_gain"
    const val METRIC_CALORIES = "dashboard_metric_value_calories"
    const val METRIC_ALTITUDE = "dashboard_metric_value_altitude"
    const val METRIC_POWER = "dashboard_metric_value_power"
}
```

### Step 2: Apply tags to `MetricItem` and `MetricsPager`

- [ ] In `MetricItem.kt`, add the import `androidx.compose.ui.platform.testTag` and change the value `TextMMD` plus the function signature:

```kotlin
@Composable
fun MetricItem(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueTestTag: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_TINY / 2),
    ) {
        TextMMD(
            text = label.uppercase(),
            style = DashboardTextStyles.caption,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            TextMMD(
                text = value,
                style = DashboardTextStyles.metricValue,
                modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier,
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(DesignConstants.PADDING_TINY / 2))
                TextMMD(
                    text = unit,
                    style = DashboardTextStyles.unit,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = DesignConstants.PADDING_TINY),
                )
            }
        }
    }
}
```

- [ ] In `MetricsPager.kt`, add imports `androidx.compose.ui.platform.testTag` and `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`, tag the pager itself, and pass `valueTestTag` on every `MetricItem` call:

```kotlin
    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().testTag(DashboardTestTags.METRICS_PAGER),
        horizontalAlignment = Alignment.CenterHorizontally,
        flingBehavior =
            PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = snap(),
            ),
    ) { pageIndex ->
```

In `PrimaryMetricsPage`, update the four `MetricItem` calls:

```kotlin
                    if (settings.showDistance) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_distance),
                            value = metrics.distanceKm,
                            unit = metrics.distanceUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_DISTANCE,
                        )
                    }
                    if (settings.showMovingTime) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_moving_time),
                            value = metrics.movingTime,
                            unit = "",
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_MOVING_TIME,
                        )
                    }
```

```kotlin
                    if (settings.showAverageSpeed) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_avg_speed),
                            value = metrics.averageSpeedKmh,
                            unit = metrics.speedUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_AVG_SPEED,
                        )
                    }
                    if (settings.showGrade) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_grade),
                            value = metrics.gradePercent,
                            unit = "%",
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_GRADE,
                        )
                    }
```

In `SecondaryMetricsPage`, update the five `MetricItem` calls:

```kotlin
                    if (settings.showMaxSpeed) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_max_speed),
                            value = metrics.maxSpeedKmh,
                            unit = metrics.speedUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_MAX_SPEED,
                        )
                    }
                    if (settings.showElevationGain) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_elevation_gain),
                            value = metrics.elevationGainM,
                            unit = metrics.altitudeUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_ELEVATION_GAIN,
                        )
                    }
```

```kotlin
                    if (settings.showCalories) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_calories),
                            value = metrics.caloriesKcal,
                            unit = "kcal",
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_CALORIES,
                        )
                    }
                    if (settings.showAltitude) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_altitude),
                            value = metrics.altitudeM,
                            unit = metrics.altitudeUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_ALTITUDE,
                        )
                    }
                    if (settings.showPower) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_power),
                            value = metrics.powerWatts,
                            unit = "W",
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_POWER,
                        )
                    }
```

### Step 3: Tag `SpeedHero`, `Compass`, `InfoBar`, `StatusIndicator`

- [ ] In `SpeedHero.kt`, add imports `androidx.compose.ui.platform.testTag` and `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`, and tag the value text:

```kotlin
            TextMMD(
                text = speed,
                style = DashboardTextStyles.hero(heroSize),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.SPEED_VALUE),
            )
```

- [ ] In `Compass.kt`, add imports `androidx.compose.ui.platform.testTag` and `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`, and tag the bearing text:

```kotlin
                TextMMD(
                    text = "${(bearing ?: 0f).toInt()}°",
                    style = DashboardTextStyles.metricValue,
                    modifier = Modifier.testTag(DashboardTestTags.COMPASS_BEARING),
                )
```

- [ ] In `InfoBar.kt`, add imports `androidx.compose.ui.platform.testTag` and `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`, and tag the GPS-quality, heart-rate, cadence, and disconnected texts:

```kotlin
        TextMMD(
            text =
                stringResource(
                    R.string.dashboard_gps_accuracy,
                    metrics.gpsAccuracyM,
                    metrics.altitudeUnit,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.testTag(DashboardTestTags.GPS_QUALITY),
        )
        metrics.heartRateBpm?.let { bpm ->
            val heartRateText =
                metrics.heartRateZone?.let { zone ->
                    stringResource(R.string.dashboard_heart_rate_zone, bpm, zone)
                } ?: stringResource(R.string.dashboard_heart_rate, bpm)
            TextMMD(
                text = heartRateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(DashboardTestTags.HEART_RATE_VALUE),
            )
        }
        metrics.cadenceRpm?.let {
            TextMMD(
                text = stringResource(R.string.dashboard_cadence, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(DashboardTestTags.CADENCE_VALUE),
            )
        }
        if (sensorPaired && !sensorConnected) {
            TextMMD(
                text = stringResource(R.string.dashboard_sensor_disconnected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(DashboardTestTags.SENSOR_DISCONNECTED),
            )
        }
```

- [ ] In `StatusIndicator.kt`, add imports `androidx.compose.ui.platform.testTag` and `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`, and tag the status text:

```kotlin
        TextMMD(
            text =
                when (status) {
                    TrackingStatus.TRACKING -> stringResource(R.string.dashboard_status_recording)
                    TrackingStatus.PAUSED -> stringResource(R.string.dashboard_status_paused)
                    TrackingStatus.AUTO_PAUSED -> stringResource(R.string.dashboard_status_auto_paused)
                    TrackingStatus.IDLE -> stringResource(R.string.dashboard_status_ready)
                },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.testTag(DashboardTestTags.STATUS_INDICATOR),
        )
```

### Step 4: Tag the start/pause and stop/reset buttons

- [ ] In `DashboardActions.kt`, add import `androidx.compose.ui.platform.testTag`, and change the two `ButtonMMD` modifiers:

```kotlin
            ButtonMMD(
                modifier =
                    (if (showSecondary) Modifier.weight(primaryWeight) else Modifier.fillMaxWidth())
                        .testTag(DashboardTestTags.START_PAUSE_BUTTON),
                onClick = { onAction(DashboardAction.OnToggleTrackingClick) },
            ) {
```

```kotlin
                ButtonMMD(
                    modifier = Modifier.weight(secondaryWeight).testTag(DashboardTestTags.STOP_RESET_BUTTON),
                    onClick = {
                        if (status != TrackingStatus.IDLE) {
                            onAction(DashboardAction.OnStopClick)
                        } else {
                            onAction(DashboardAction.OnResetClick)
                        }
                    },
                ) {
```

(`DashboardTestTags` is in the same package, `com.speedevand.inkride.dashboard.presentation`, but this file is in the `.components` subpackage, so also add the import `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`.)

### Step 5: Tag the record-lap and goal buttons

- [ ] In `DashboardTopBar.kt`, add imports `androidx.compose.ui.platform.testTag` and `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`, and tag the two `IconButton`s:

```kotlin
            if (isActiveRide) {
                IconButton(onClick = onRecordLap, modifier = Modifier.testTag(DashboardTestTags.RECORD_LAP_BUTTON)) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = stringResource(R.string.dashboard_action_lap),
                    )
                }
                IconButton(onClick = onOpenGoal, modifier = Modifier.testTag(DashboardTestTags.GOAL_BUTTON)) {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = stringResource(R.string.dashboard_action_goal),
                    )
                }
            }
```

### Step 6: Tag goal/lap status text and the goal-sheet input

- [ ] In `LapGoalControls.kt`, add imports `androidx.compose.ui.platform.testTag` and `com.speedevand.inkride.dashboard.presentation.DashboardTestTags`, and update `LapGoalStatus`'s two `TextMMD`s plus the goal sheet's text field and set button:

```kotlin
        state.goal?.let { goal ->
            TextMMD(
                text =
                    if (goal.reached) {
                        stringResource(R.string.dashboard_goal_reached)
                    } else {
                        stringResource(R.string.dashboard_goal_remaining, goal.remainingValue, goal.unitLabel)
                    },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(DashboardTestTags.GOAL_STATUS),
            )
        }

        state.lastLap?.let { lap ->
            TextMMD(
                text =
                    stringResource(
                        R.string.dashboard_last_lap,
                        lap.distance,
                        lap.time,
                        lap.averageSpeed,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(DashboardTestTags.LAST_LAP_STATUS),
            )
        }
```

```kotlin
            TextFieldMMD(
                value = value,
                onValueChange = { value = it },
                label = {
                    TextMMD(
                        text =
                            if (isDistance) {
                                "${stringResource(R.string.dashboard_goal_distance_hint)} ($distanceUnit)"
                            } else {
                                stringResource(R.string.dashboard_goal_duration_hint)
                            },
                    )
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (isDistance) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.GOAL_VALUE_FIELD),
            )
```

```kotlin
                ButtonMMD(
                    modifier = Modifier.weight(1f).testTag(DashboardTestTags.GOAL_SET_BUTTON),
                    onClick = {
                        buildGoal(isDistance, value, imperial)?.let { onAction(DashboardAction.OnSetGoal(it)) }
                        onDismiss()
                    },
                ) {
                    TextMMD(text = stringResource(R.string.dashboard_goal_set))
                }
```

- [ ] **Verify Steps 1-6 compile:**

Run: `./gradlew :feature:dashboard:presentation:assembleDebug`
Expected: `BUILD SUCCESSFUL`

### Step 7: Create the fake sensor sources

- [ ] Create `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/FakeRideSensorDataSource.kt`:

```kotlin
package com.speedevand.inkride.tracking.fakes

import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.SensorError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for [RideSensorDataSource], swapped in via Koin override in
 * instrumented tests so an emulator's absent real GPS/barometer isn't a
 * blocker. `start`/`stop` are no-ops; tests drive the sample flow directly
 * with [emit].
 */
class FakeRideSensorDataSource : RideSensorDataSource {
    private val samplesFlow =
        MutableSharedFlow<RideSensorSample>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun observeSamples(): Flow<RideSensorSample> = samplesFlow.asSharedFlow()

    override fun start(): EmptyResult<SensorError> = Result.Success(Unit)

    override fun stop() = Unit

    fun emit(sample: RideSensorSample) {
        check(samplesFlow.tryEmit(sample)) { "Failed to emit $sample" }
    }
}
```

- [ ] Create `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/FakeBleSensorDataSource.kt`:

```kotlin
package com.speedevand.inkride.tracking.fakes

import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for [BleSensorDataSource], swapped in via Koin override in
 * instrumented tests so an emulator's absent BLE HRM/cadence hardware isn't a
 * blocker. `connect`/`disconnect` are no-ops; tests drive the sample flow
 * directly with [emit].
 */
class FakeBleSensorDataSource : BleSensorDataSource {
    private val samplesFlow =
        MutableSharedFlow<BleSample>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun observeSamples(): Flow<BleSample> = samplesFlow.asSharedFlow()

    override fun connect(
        hrmAddress: String?,
        cadenceAddress: String?,
    ) = Unit

    override fun disconnect() = Unit

    fun emit(sample: BleSample) {
        check(samplesFlow.tryEmit(sample)) { "Failed to emit $sample" }
    }
}
```

### Step 8: Create the synthetic ride-sample builder

- [ ] Create `app/src/androidTest/java/com/speedevand/inkride/tracking/support/RideSamples.kt`:

```kotlin
package com.speedevand.inkride.tracking.support

import com.speedevand.inkride.core.domain.tracking.RideSensorSample

/**
 * Builds [RideSensorSample]s for a synthetic straight-line ride heading due
 * north (bearing 0°), one step per real second. [stepIndex] starts at 1;
 * latitude advances by exactly `speedKmh` worth of distance per step so the
 * fed lat/lon path and [RideSensorSample.speedFromGpsMps] agree —
 * `RideMetricsCalculator` cross-validates the two and would otherwise reject
 * the fix as an implausible jump.
 *
 * Callers pass `nowMs` (real `System.currentTimeMillis()` at the moment of
 * emission) rather than a precomputed timestamp, so wall-clock-gated logic in
 * `RideTracker`/`RideMetricsCalculator` (auto-pause delay, cadence timeout,
 * GPS cold-start warm-up) sees consistent, real elapsed time between samples.
 */
object RideSamples {
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    const val START_LATITUDE = 52.2297
    const val START_LONGITUDE = 21.0122

    /**
     * A moving fix `stepIndex` seconds into the ride at `speedKmh`.
     * [includeGpsFix] = false simulates a GPS dropout: lat/lon/speed/accuracy
     * are null but [altitudeM] still flows through as
     * [RideSensorSample.altitudeFromBarometerM], matching how
     * `AndroidRideSensorDataSource` keeps emitting barometer-only samples
     * when GPS is unavailable.
     */
    fun movingSample(
        stepIndex: Int,
        nowMs: Long,
        speedKmh: Double = 20.0,
        accuracyM: Float = 5f,
        satelliteCount: Int = 8,
        bearingDegrees: Float = 0f,
        altitudeM: Double = 100.0,
        includeGpsFix: Boolean = true,
    ): RideSensorSample {
        val speedMps = speedKmh / 3.6
        val deltaLatDeg = (speedMps * stepIndex) / METERS_PER_DEGREE_LATITUDE
        return RideSensorSample(
            timestampMs = nowMs,
            latitude = if (includeGpsFix) START_LATITUDE + deltaLatDeg else null,
            longitude = if (includeGpsFix) START_LONGITUDE else null,
            altitudeFromGpsM = if (includeGpsFix) altitudeM else null,
            altitudeFromBarometerM = altitudeM,
            speedFromGpsMps = if (includeGpsFix) speedMps else null,
            accuracyM = if (includeGpsFix) accuracyM else null,
            bearingDegrees = if (includeGpsFix) bearingDegrees else null,
            satelliteCount = if (includeGpsFix) satelliteCount else null,
        )
    }

    /** A stationary GPS fix (0 speed) at the ride's start position. */
    fun stationarySample(
        nowMs: Long,
        accuracyM: Float = 5f,
        satelliteCount: Int = 8,
    ): RideSensorSample =
        RideSensorSample(
            timestampMs = nowMs,
            latitude = START_LATITUDE,
            longitude = START_LONGITUDE,
            altitudeFromGpsM = 100.0,
            altitudeFromBarometerM = 100.0,
            speedFromGpsMps = 0.0,
            accuracyM = accuracyM,
            bearingDegrees = 0f,
            satelliteCount = satelliteCount,
        )
}
```

### Step 9: Create the Compose text-reading helpers

- [ ] Create `app/src/androidTest/java/com/speedevand/inkride/tracking/support/ComposeTestHelpers.kt`:

```kotlin
package com.speedevand.inkride.tracking.support

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/**
 * The literal text rendered by a single tagged text node (e.g. a `TextMMD`
 * carrying a `Modifier.testTag(...)`). Reading semantics directly, rather
 * than matching on formatted/localized strings, keeps assertions robust to
 * decimal-separator/unit differences.
 */
fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }

fun ComposeTestRule.textOf(tag: String): String = onNodeWithTag(tag).assertIsDisplayed().text()

/**
 * Polls a tagged node's text against [predicate] on a real wall clock — ride
 * metrics update from a background coroutine (the fake sensor sources feed
 * `RideTracker` on `Dispatchers.Default`), not Compose's test clock, so this
 * relies on [ComposeTestRule.waitUntil]'s real-time polling.
 */
fun ComposeTestRule.waitUntilTagText(
    tag: String,
    timeoutMillis: Long = 15_000L,
    predicate: (String) -> Boolean,
) {
    waitUntil(timeoutMillis) {
        runCatching { predicate(textOf(tag)) }.getOrDefault(false)
    }
}
```

### Step 10: Create the shared test base class

- [ ] Create `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingE2ETestBase.kt`:

```kotlin
package com.speedevand.inkride.tracking

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.speedevand.inkride.MainActivity
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.tracking.fakes.FakeBleSensorDataSource
import com.speedevand.inkride.tracking.fakes.FakeRideSensorDataSource
import com.speedevand.inkride.tracking.service.TrackingService
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
}
```

### Step 11: Write the happy-path test

- [ ] Create `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingHappyPathTest.kt`:

```kotlin
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
```

- [ ] **Step 12: Run the happy-path test on a running emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingHappyPathTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed. (Requires an emulator already running — `adb devices` should list one.)

- [ ] **Step 13: Commit**

```bash
git add feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardTestTags.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/MetricItem.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/MetricsPager.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/SpeedHero.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/Compass.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/InfoBar.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/StatusIndicator.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/DashboardActions.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/DashboardTopBar.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/LapGoalControls.kt \
        app/src/androidTest
git commit -m "test: add ride-tracking E2E test infra and happy-path test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 3: Manual pause/resume and multi-ride reset test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingManualPauseResumeAndMultiRideTest.kt`

**Interfaces:**
- Consumes: `RideTrackingE2ETestBase`, `RideSamples.movingSample`, `ComposeTestRule.textOf`/`waitUntilTagText`, `DashboardTestTags.*`.

- [ ] **Step 1: Write the test**

```kotlin
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
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingManualPauseResumeAndMultiRideTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingManualPauseResumeAndMultiRideTest.kt
git commit -m "test: add manual pause/resume and multi-ride reset E2E test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 4: Auto-pause / auto-resume test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingAutoPauseTest.kt`

**Interfaces:**
- Consumes: same as Task 3, plus `RideSamples.stationarySample`.

- [ ] **Step 1: Write the test**

```kotlin
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
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingAutoPauseTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingAutoPauseTest.kt
git commit -m "test: add auto-pause/auto-resume E2E test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 5: BLE sensor-loss test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingBleSensorLossTest.kt`

**Interfaces:**
- Consumes: `RideTrackingE2ETestBase`, `RideSamples.movingSample`, `fakeBleSource.emit(BleSample)`, `ComposeTestRule` node-existence assertions.

- [ ] **Step 1: Write the test**

```kotlin
package com.speedevand.inkride.tracking

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

/**
 * A BLE sensor drop clears its readings immediately (BleSample.connected
 * docs: "readings cleared" as soon as a sensor drops) — this checks the UI
 * reflects that, and that GPS-derived metrics are unaffected.
 */
class RideTrackingBleSensorLossTest : RideTrackingE2ETestBase() {
    @Test
    fun bleDisconnectHidesHrAndCadenceWhileGpsMetricsKeepUpdating() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        fakeSensorSource.emit(RideSamples.movingSample(stepIndex = 1, nowMs = System.currentTimeMillis()))
        fakeBleSource.emit(
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = 150,
                cadenceRpm = 90,
                connected = true,
                cadenceUpdatedAtMs = System.currentTimeMillis(),
            ),
        )

        composeTestRule.waitUntilTagText(DashboardTestTags.HEART_RATE_VALUE) { it.contains("150") }
        assertThat(composeTestRule.textOf(DashboardTestTags.CADENCE_VALUE)).isEqualTo("90")
        composeTestRule.onNodeWithTag(DashboardTestTags.SENSOR_DISCONNECTED).assertDoesNotExist()

        val distanceBeforeDisconnect = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()

        fakeBleSource.emit(
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = null,
                cadenceRpm = null,
                connected = false,
            ),
        )

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(DashboardTestTags.SENSOR_DISCONNECTED)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(DashboardTestTags.HEART_RATE_VALUE).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DashboardTestTags.CADENCE_VALUE).assertDoesNotExist()

        // GPS-derived metrics keep moving regardless of the BLE drop.
        for (step in 2..6) {
            fakeSensorSource.emit(RideSamples.movingSample(stepIndex = step, nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }
        val distanceAfter = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(distanceAfter).isGreaterThan(distanceBeforeDisconnect)
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingBleSensorLossTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingBleSensorLossTest.kt
git commit -m "test: add BLE sensor-loss E2E test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 6: GPS quality and GPS-dropout test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingGpsQualityTest.kt`

**Interfaces:**
- Consumes: `RideTrackingE2ETestBase`, `RideSamples.movingSample(includeGpsFix = false)` for the dropout leg.

- [ ] **Step 1: Write the test**

```kotlin
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
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingGpsQualityTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingGpsQualityTest.kt
git commit -m "test: add GPS quality / dropout E2E test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 7: Lap-recording test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingLapRecordingTest.kt`

**Interfaces:**
- Consumes: `DashboardTestTags.RECORD_LAP_BUTTON`, `DashboardTestTags.LAST_LAP_STATUS`.

- [ ] **Step 1: Write the test**

```kotlin
package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.startsWith
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

class RideTrackingLapRecordingTest : RideTrackingE2ETestBase() {
    @Test
    fun recordingALapShowsItsSummaryInTheStatusStrip() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        // 10 steps at 20 km/h ≈ 55m, comfortably past RideTracker's
        // minLapDistanceKm (10m) floor for a lap to be recorded.
        for (step in 1..10) {
            fakeSensorSource.emit(RideSamples.movingSample(stepIndex = step, nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }

        composeTestRule.onNodeWithTag(DashboardTestTags.RECORD_LAP_BUTTON).performClick()

        composeTestRule.waitUntilTagText(DashboardTestTags.LAST_LAP_STATUS) { it.startsWith("Last lap:") }
        val lapText = composeTestRule.textOf(DashboardTestTags.LAST_LAP_STATUS)
        assertThat(lapText).startsWith("Last lap:")
        assertThat(lapText).contains("km")
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingLapRecordingTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingLapRecordingTest.kt
git commit -m "test: add lap-recording E2E test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 8: Goal progress and over-speed alert test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingGoalAndAlertTest.kt`

**Interfaces:**
- Consumes: `DashboardTestTags.GOAL_BUTTON/GOAL_VALUE_FIELD/GOAL_SET_BUTTON/GOAL_STATUS`, overrides `RideTrackingE2ETestBase.seedSettings()` to add an `AlertConfig`, and collects `RideTracker.alerts` directly via `GlobalContext.get().get<RideTracker>()`.

- [ ] **Step 1: Write the test**

```kotlin
package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.domain.settings.AlertConfig
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.tracking.RideAlert
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.waitUntilTagText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test
import org.koin.core.context.GlobalContext

class RideTrackingGoalAndAlertTest : RideTrackingE2ETestBase() {
    override fun seedSettings(): UserSettings =
        super.seedSettings().copy(alerts = AlertConfig(maxSpeedKmh = 15.0))

    @Test
    fun settingADistanceGoalShowsProgressThenReached() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_BUTTON).performClick()
        // Distance is the default goal type in the sheet; 0.05 km ≈ 9-10s of
        // riding at 20 km/h.
        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_VALUE_FIELD).performTextInput("0.05")
        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_SET_BUTTON).performClick()

        composeTestRule.waitUntilTagText(DashboardTestTags.GOAL_STATUS) { it.contains("km") }

        for (step in 1..15) {
            fakeSensorSource.emit(RideSamples.movingSample(stepIndex = step, nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }

        composeTestRule.waitUntilTagText(DashboardTestTags.GOAL_STATUS, timeoutMillis = 20_000L) {
            it == "Goal reached!"
        }
    }

    @Test
    fun overSpeedSampleEmitsExactlyOneAlert() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        val alerts = mutableListOf<RideAlert>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        collectorScope.launch {
            GlobalContext.get().get<RideTracker>().alerts.collect { alerts.add(it) }
        }

        // 20 km/h steadily exceeds the 15 km/h threshold seeded above; the
        // alert is edge-triggered so it must fire exactly once even though
        // every sample after the crossing stays above the limit.
        for (step in 1..10) {
            fakeSensorSource.emit(RideSamples.movingSample(stepIndex = step, nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }

        collectorScope.cancel()
        assertThat(alerts).hasSize(1)
        assertThat(alerts.first()).isInstanceOf(RideAlert.OverSpeed::class)
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingGoalAndAlertTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingGoalAndAlertTest.kt
git commit -m "test: add ride-goal progress and over-speed alert E2E tests

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 9: Foreground service lifecycle test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingServiceLifecycleTest.kt`

**Interfaces:**
- Consumes: `TrackingService` (from `:feature:tracking:data`, already a dependency of `:app`), `android.app.ActivityManager`.

- [ ] **Step 1: Write the test**

```kotlin
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
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.RideTrackingServiceLifecycleTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingServiceLifecycleTest.kt
git commit -m "test: add TrackingService foreground-lifecycle E2E test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm"
```

---

## Task 10: Full-suite run and JVM regression check

**Files:** none (verification-only task).

- [ ] **Step 1: Run the entire instrumented suite together**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, 9 tests passed (Happy Path, Manual Pause/Resume+Multi-ride, Auto-pause, BLE Sensor Loss, GPS Quality, Lap Recording, Goal+Alert ×2, Service Lifecycle), 0 failures. Run it 3 times in a row to confirm no flake (per the spec's Definition of Done).

- [ ] **Step 2: Confirm no regression in the existing JVM suite and lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: `BUILD SUCCESSFUL` — the tag additions in Task 2 are additive `Modifier`s only and must not change any existing unit-tested behavior.

- [ ] **Step 3: Report**

No commit for this task — it only verifies Tasks 1-9. If any run is flaky, return to the relevant task and tighten its `waitUntilTagText`/`waitUntil` timeout or the sample pacing before considering the suite done.
