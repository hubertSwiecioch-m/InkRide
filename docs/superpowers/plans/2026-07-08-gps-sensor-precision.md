# GPS & Sensor Precision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the seven independent accuracy improvements from `docs/superpowers/specs/2026-07-08-gps-sensor-precision-design.md` — rotation-vector heading, a cadence dropout timeout, heart-rate outlier rejection, Tanaka heart-rate zones, a documented decision to leave calories alone, altitude-adjusted air density, and a Kalman filter for GPS position.

**Architecture:** Each item is a small, independently-committable change layered onto the existing tracking pipeline (`:core:domain` tracking package + the Android-facing data sources in `:feature:tracking:data` / `:feature:ble:data`, surfaced through `:feature:dashboard:presentation`). New pure logic (filters/calculators) is extracted into `:core:domain` following the project's established `HeadingSmoother` pattern — plain Kotlin, unit-tested in isolation, consumed by a thin Android-facing class. Nothing in `RideMetricsCalculator`'s existing 781-line test suite is touched; the new Kalman filter runs one layer up, inside `AndroidRideSensorDataSource`, so it changes what `RideMetricsCalculator` receives without changing the calculator itself.

**Tech Stack:** Kotlin, Koin DI, JUnit5 + assertk (test style already established in this repo), Kotlin coroutines (`kotlinx-coroutines-test` for `RideTracker` tests).

## Global Constraints

- Cadence dropout timeout: fixed at 3000 ms, not user-configurable.
- Heart-rate plausible range: 30–220 bpm; max plausible rate of change: 60 bpm per second (tunable constructor default, not user-facing).
- Heart-rate zones: Tanaka formula `HRmax = 208 − 0.7 × age`; bands Z1 <60%, Z2 60–70%, Z3 70–80%, Z4 80–90%, Z5 ≥90% of HRmax.
- Air density: ISA approximation `ρ(h) = 1.225 × (1 − 2.25577e-5 × h)^5.25588`, falls back to 1.225 kg/m³ (sea level) when altitude is unknown.
- Kalman filter gating: chi-square bound for 2 degrees of freedom at ~99% confidence = 9.21.
- No new `UserSettings` fields and no database schema/migration changes anywhere in this round.
- `CaloriesEstimator`'s formula is explicitly out of scope — item 5 is a documentation-only change.
- The Kalman filter lives in `AndroidRideSensorDataSource` (the Android-facing data source), never inside `RideMetricsCalculator`. `RideMetricsCalculator.kt` and `RideMetricsCalculatorTest.kt` must not be modified by this plan, and the full existing `:core:domain:test` suite must keep passing unchanged after every task.
- `RideTracker`'s constructor is called positionally in `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/TrackingDataModule.kt:17` (`RideTracker(get(), get(), get(), get(), get(), get(), get())`, matching its first 7 required parameters). Any new constructor parameter must be added as a trailing-defaulted parameter *after* those 7, so this line keeps compiling unchanged — verify this file still compiles after every task that touches `RideTracker`'s constructor.

---

### Task 1: Heading — switch to `TYPE_ROTATION_VECTOR`

**Files:**
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt`

**Interfaces:**
- Consumes: `HeadingSmoother.update(magneticAzimuthDeg: Float, declinationDeg: Float): HeadingUpdate` (unchanged, from `core/domain/.../tracking/HeadingSmoother.kt`) — this task changes only what raw sensor feeds the azimuth into that call.
- Produces: no change to any public interface. `RideSensorSample.bearingDegrees` semantics are unchanged.

There is no existing unit test for this class (it's an Android-framework sensor/location glue class; the project's established pattern, per `AGENTS.md`/`CLAUDE.md` architecture notes, tests pure domain logic and leaves this boundary layer to compile/manual verification — consistent with how `HeadingSmoother` itself was already extracted specifically so its logic *could* be unit tested, while the sensor registration around it stays untested). This task is verified by compilation and a manual on-device check instead of an automated test.

- [ ] **Step 1: Replace the accelerometer/magnetometer sensor fields with a rotation-vector sensor**

In `AndroidRideSensorDataSource.kt`, replace:

```kotlin
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
```

with:

```kotlin
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
```

- [ ] **Step 2: Rename the reliability flag and update its comment**

Replace:

```kotlin
    // True while the magnetometer reports UNRELIABLE/LOW calibration accuracy.
    // While set, magnetometer-derived heading is suppressed (GPS course-over-
    // ground is still used when moving). Unknown accuracy is treated as usable
    // so devices that never fire onAccuracyChanged still get a compass.
    private var isMagnetometerUnreliable: Boolean = false
```

with:

```kotlin
    // True while the fused rotation vector reports UNRELIABLE/LOW accuracy
    // (e.g. right after start, before gyro/mag fusion has converged). While
    // set, rotation-vector-derived heading is suppressed (GPS course-over-
    // ground is still used when moving). Unknown accuracy is treated as usable
    // so devices that never fire onAccuracyChanged still get a compass.
    private var isOrientationSensorUnreliable: Boolean = false
```

- [ ] **Step 3: Replace the orientation listener body**

Replace the whole `localOrientationListener` object:

```kotlin
        val localOrientationListener =
            object : SensorEventListener {
                private var gravity: FloatArray? = null
                private var geomagnetic: FloatArray? = null

                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null) return
                    if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) gravity = event.values.clone()
                    if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) geomagnetic = event.values.clone()

                    // Suppress heading while the magnetometer is known to be miscalibrated.
                    // A figure-eight calibration is needed; until then the azimuth is
                    // garbage and would point the compass in a random direction.
                    // (GPS course-over-ground, set in the location listener, is unaffected.)
                    if (isMagnetometerUnreliable) return

                    if (gravity != null && geomagnetic != null) {
                        val r = FloatArray(9)
                        val i = FloatArray(9)
                        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(r, orientation)
                            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()

                            val update = headingSmoother.update(azimuth, magneticDeclinationDeg)
                            lastHeading = update.smoothedHeadingDeg
                            lastHeadingTimestampMs = System.currentTimeMillis()

                            // Throttle emissions to ~2° steps to avoid flooding the
                            // sample flow (and the E-Ink redraw) with micro-changes.
                            if (update.shouldEmit) emitSample()
                        }
                    }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) {
                    // Track magnetometer calibration health. UNRELIABLE/LOW mean the
                    // compass needs recalibration and its heading can't be trusted.
                    if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                        isMagnetometerUnreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                    }
                }
            }
```

with:

```kotlin
        val localOrientationListener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                    // Suppress heading while the fused rotation vector is known to be
                    // unreliable. (GPS course-over-ground, set in the location
                    // listener, is unaffected.)
                    if (isOrientationSensorUnreliable) return

                    val r = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(r, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(r, orientation)
                    val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()

                    val update = headingSmoother.update(azimuth, magneticDeclinationDeg)
                    lastHeading = update.smoothedHeadingDeg
                    lastHeadingTimestampMs = System.currentTimeMillis()

                    // Throttle emissions to ~2° steps to avoid flooding the
                    // sample flow (and the E-Ink redraw) with micro-changes.
                    if (update.shouldEmit) emitSample()
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) {
                    // Track rotation-vector fusion health. UNRELIABLE/LOW mean the
                    // fused heading can't be trusted yet.
                    if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                        isOrientationSensorUnreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                    }
                }
            }
```

- [ ] **Step 4: Update sensor registration**

Replace:

```kotlin
        accelerometer?.also {
            sensorManager.registerListener(
                localOrientationListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                callbackHandler,
            )
        }

        magnetometer?.also {
            sensorManager.registerListener(
                localOrientationListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                callbackHandler,
            )
        }
```

with:

```kotlin
        rotationVectorSensor?.also {
            sensorManager.registerListener(
                localOrientationListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                callbackHandler,
            )
        }
```

- [ ] **Step 5: Update the `stop()` reset line**

Replace:

```kotlin
        isMagnetometerUnreliable = false
```

with:

```kotlin
        isOrientationSensorUnreliable = false
```

- [ ] **Step 6: Compile-check**

Run: `./gradlew :feature:tracking:data:assembleDebug`
Expected: BUILD SUCCESSFUL, no references remain to `accelerometer`, `magnetometer`, or `isMagnetometerUnreliable` anywhere in the file (confirm with `grep -n "accelerometer\|magnetometer\|isMagnetometerUnreliable" feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt` — expect no output).

- [ ] **Step 7: Manual on-device verification**

Install the debug build on a device, open the dashboard, and confirm: the compass heading still updates while stationary (rotation-vector-derived) and while riding above ~2 m/s the heading still switches to GPS course-over-ground (unchanged logic in `emitSample()`). Confirm the compass doesn't freeze or point in a fixed wrong direction right after app start (the accuracy-unreliable suppression should still engage briefly then clear as fusion converges).

- [ ] **Step 8: Commit**

```bash
git add feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt
git commit -m "$(cat <<'EOF'
Derive heading from the fused rotation-vector sensor instead of raw accel+mag

The gyro-fused rotation vector is less susceptible to bump/vibration
noise transmitted through the bike frame than the raw accelerometer/
magnetometer pair HeadingSmoother previously had to filter out.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Cadence — 3-second dropout timeout

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSample.kt`
- Modify: `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:**
- Produces: `BleSample.cadenceUpdatedAtMs: Long?` — timestamp of the last CSC-derived cadence value, null when never reported. Consumed by `RideTracker`.
- Produces (private to `RideTracker`, documented for context): `cadenceOrZeroIfStale(rawCadenceRpm: Int?, nowMs: Long): Int?`.

- [ ] **Step 1: Write the failing tests in `RideTrackerTest.kt`**

Add these two test methods to the `RideTrackerTest` class, after the existing `over-speed alert fires once when speed crosses the threshold` test and before the `newTracker` helper function:

```kotlin
    @Test
    fun `cadence drops to zero after 3 seconds without a fresh BLE update`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(
                BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88, cadenceUpdatedAtMs = 0L, connected = true),
            )
            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(88)

            // A GPS fix 3.5s later with no intervening cadence update: cadence
            // should read 0 (the sensor stopped notifying), while HR is unaffected.
            sensor.samples.emit(sampleAt(3_500L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(0)
            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)
        }

    @Test
    fun `a fresh cadence update before the timeout keeps the real value`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, cadenceRpm = 88, cadenceUpdatedAtMs = 0L, connected = true))
            ble.samples.emit(BleSample(timestampMs = 2_000L, cadenceRpm = 90, cadenceUpdatedAtMs = 2_000L, connected = true))
            // 4500 - 2000 = 2500ms since the last cadence update, under the 3000ms timeout.
            sensor.samples.emit(sampleAt(4_500L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(90)
        }
```

- [ ] **Step 2: Run the tests to verify they fail (compile error — `cadenceUpdatedAtMs` doesn't exist yet)**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: compilation FAILURE — `BleSample` has no parameter `cadenceUpdatedAtMs`.

- [ ] **Step 3: Add `cadenceUpdatedAtMs` to `BleSample`**

In `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSample.kt`, replace:

```kotlin
data class BleSample(
    val timestampMs: Long,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val wheelRevolutions: Long? = null,
    val connected: Boolean = false,
)
```

with:

```kotlin
data class BleSample(
    val timestampMs: Long,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val wheelRevolutions: Long? = null,
    val connected: Boolean = false,
    // Wall-clock timestamp of the last CSC notification that actually carried
    // a cadence value. Null when cadence has never been reported. Distinct
    // from [timestampMs] (this emission's own time) because most CSC sensors
    // keep the last cadence cached and re-emit it alongside unrelated HR
    // notifications, without a fresh crank event — this field lets a
    // consumer tell "cadence is still arriving" from "cadence is stale".
    val cadenceUpdatedAtMs: Long? = null,
)
```

- [ ] **Step 4: Track and emit `cadenceUpdatedAtMs` in `AndroidBleSensorDataSource`**

In `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt`, add a new field next to the other `latest*` fields:

```kotlin
    @Volatile
    private var latestCadence: Int? = null

    @Volatile
    private var lastCadenceUpdateAtMs: Long? = null

```

(insert the new field and its blank line right after the existing `latestCadence` declaration, before `latestWheelRevolutions`).

In `disconnect()`, replace:

```kotlin
        latestHeartRate = null
        latestCadence = null
        latestWheelRevolutions = null
        emit()
```

with:

```kotlin
        latestHeartRate = null
        latestCadence = null
        lastCadenceUpdateAtMs = null
        latestWheelRevolutions = null
        emit()
```

In the `onConnectionStateChange` `STATE_DISCONNECTED` branch, replace:

```kotlin
                        latestHeartRate = null
                        latestCadence = null
                        latestWheelRevolutions = null
                        emit()
```

with:

```kotlin
                        latestHeartRate = null
                        latestCadence = null
                        lastCadenceUpdateAtMs = null
                        latestWheelRevolutions = null
                        emit()
```

In `emit()`, replace:

```kotlin
    private fun emit() {
        samples.value =
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = latestHeartRate,
                cadenceRpm = latestCadence,
                wheelRevolutions = latestWheelRevolutions,
                connected = liveAddresses.isNotEmpty(),
            )
    }
```

with:

```kotlin
    private fun emit() {
        samples.value =
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = latestHeartRate,
                cadenceRpm = latestCadence,
                wheelRevolutions = latestWheelRevolutions,
                connected = liveAddresses.isNotEmpty(),
                cadenceUpdatedAtMs = lastCadenceUpdateAtMs,
            )
    }
```

In `handleCharacteristic`'s CSC branch, replace:

```kotlin
            BleGatt.CSC_MEASUREMENT -> {
                val tracker = address?.let { cadenceTrackers[it] } ?: return
                val result = tracker.update(data) ?: return
                result.cadenceRpm?.let { latestCadence = it }
                result.wheelRevolutions?.let { latestWheelRevolutions = it }
                emit()
            }
```

with:

```kotlin
            BleGatt.CSC_MEASUREMENT -> {
                val tracker = address?.let { cadenceTrackers[it] } ?: return
                val result = tracker.update(data) ?: return
                result.cadenceRpm?.let {
                    latestCadence = it
                    lastCadenceUpdateAtMs = System.currentTimeMillis()
                }
                result.wheelRevolutions?.let { latestWheelRevolutions = it }
                emit()
            }
```

- [ ] **Step 5: Add the staleness check and wire it into `RideTracker`**

In `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`, add two new fields next to `lowSpeedSinceMs`:

```kotlin
    // Timestamp (BLE sample clock) of the most recent cadence value actually
    // reported by a CSC sensor. Distinguishes "cadence is still arriving"
    // from "cadence is stale because the crank stopped and the sensor went
    // quiet" — most CSC sensors stop notifying entirely rather than sending
    // an explicit 0 rpm once the crank stops turning.
    @Volatile
    private var lastCadenceUpdateAtMs: Long? = null

    private val cadenceTimeoutMs: Long = 3_000L

```

Add a new private method (near `evaluateAutoPause`):

```kotlin
    /**
     * Returns 0 once more than [cadenceTimeoutMs] has passed since the last
     * actual cadence notification, instead of freezing at the last reported
     * value.
     */
    private fun cadenceOrZeroIfStale(
        rawCadenceRpm: Int?,
        nowMs: Long,
    ): Int? {
        val lastUpdate = lastCadenceUpdateAtMs ?: return rawCadenceRpm
        return if (nowMs - lastUpdate > cadenceTimeoutMs) 0 else rawCadenceRpm
    }
```

In `launchCollection()`'s `bleJob`, replace:

```kotlin
                val bleJob =
                    launch {
                        bleSensorDataSource.observeSamples().collect { ble ->
                            val updated =
                                _state.updateAndGet { current ->
                                    if (current.status == TrackingStatus.IDLE) {
                                        current
                                    } else {
                                        current.copy(
                                            metrics =
                                                current.metrics.copy(
                                                    heartRateBpm = ble.heartRateBpm,
                                                    cadenceRpm = ble.cadenceRpm,
                                                ),
                                            bleSensorConnected = ble.connected,
                                        )
                                    }
                                }
                            // HR alerts can fire from a BLE notification alone, with no
                            // intervening GPS fix.
                            evaluateAlerts(updated.status, updated.metrics)
                        }
                    }
```

with:

```kotlin
                val bleJob =
                    launch {
                        bleSensorDataSource.observeSamples().collect { ble ->
                            ble.cadenceUpdatedAtMs?.let { lastCadenceUpdateAtMs = it }
                            val updated =
                                _state.updateAndGet { current ->
                                    if (current.status == TrackingStatus.IDLE) {
                                        current
                                    } else {
                                        current.copy(
                                            metrics =
                                                current.metrics.copy(
                                                    heartRateBpm = ble.heartRateBpm,
                                                    cadenceRpm = cadenceOrZeroIfStale(ble.cadenceRpm, ble.timestampMs),
                                                ),
                                            bleSensorConnected = ble.connected,
                                        )
                                    }
                                }
                            // HR alerts can fire from a BLE notification alone, with no
                            // intervening GPS fix.
                            evaluateAlerts(updated.status, updated.metrics)
                        }
                    }
```

In the same method's GPS-sample handler, replace:

```kotlin
                                val metrics =
                                    baseMetrics.copy(
                                        heartRateBpm = current.metrics.heartRateBpm,
                                        cadenceRpm = current.metrics.cadenceRpm,
                                    )
```

with:

```kotlin
                                val metrics =
                                    baseMetrics.copy(
                                        heartRateBpm = current.metrics.heartRateBpm,
                                        cadenceRpm = cadenceOrZeroIfStale(current.metrics.cadenceRpm, sample.timestampMs),
                                    )
```

Finally, reset `lastCadenceUpdateAtMs` alongside the other per-session state. In `startNewSession()`, replace:

```kotlin
                sessionStartMs = System.currentTimeMillis()
                metricsCalculator.reset()
                lowSpeedSinceMs = null
                resetAlertState()
                resetLapBaseline()
```

with:

```kotlin
                sessionStartMs = System.currentTimeMillis()
                metricsCalculator.reset()
                lowSpeedSinceMs = null
                lastCadenceUpdateAtMs = null
                resetAlertState()
                resetLapBaseline()
```

In `stop()`, replace:

```kotlin
        metricsCalculator.reset()
        lowSpeedSinceMs = null
        resetAlertState()
        resetLapBaseline()
```

with:

```kotlin
        metricsCalculator.reset()
        lowSpeedSinceMs = null
        lastCadenceUpdateAtMs = null
        resetAlertState()
        resetLapBaseline()
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: BUILD SUCCESSFUL, all tests pass including the two new ones.

- [ ] **Step 7: Run the full `:core:domain` and `:feature:ble:data` test suites to confirm no regressions**

Run: `./gradlew :core:domain:test :feature:ble:data:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSample.kt \
        feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt \
        core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt
git commit -m "$(cat <<'EOF'
Zero cadence after 3s without a fresh BLE notification

Most CSC sensors stop notifying entirely (rather than sending 0 rpm)
once the crank stops turning, so cadence previously froze at its last
value instead of dropping to 0 like other cycling computers do.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Heart-rate outlier rejection

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeartRateFilter.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeartRateFilterTest.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Modify: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:**
- Produces: `HeartRateFilter.filter(rawBpm: Int?, timestampMs: Long): Int?` and `HeartRateFilter.reset()`.
- Consumes (in `RideTracker`): the new `HeartRateFilter`, instantiated as a constructor default, same pattern as `routeFollower: RouteFollower = RouteFollower()`.

- [ ] **Step 1: Write the failing tests**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeartRateFilterTest.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class HeartRateFilterTest {
    @Test
    fun `accepts the first plausible reading`() {
        val filter = HeartRateFilter()
        assertThat(filter.filter(rawBpm = 145, timestampMs = 0L)).isEqualTo(145)
    }

    @Test
    fun `first reading is subject to the plausible range check`() {
        val filter = HeartRateFilter()
        assertThat(filter.filter(rawBpm = 255, timestampMs = 0L)).isNull()
    }

    @Test
    fun `rejects a reading below the plausible range`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 145, timestampMs = 0L)
        assertThat(filter.filter(rawBpm = 0, timestampMs = 1_000L)).isEqualTo(145)
    }

    @Test
    fun `rejects a reading above the plausible range`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 145, timestampMs = 0L)
        assertThat(filter.filter(rawBpm = 255, timestampMs = 1_000L)).isEqualTo(145)
    }

    @Test
    fun `rejects an implausible jump between consecutive readings`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 70, timestampMs = 0L)
        // 70 -> 190 in one second is not a real heartbeat change.
        assertThat(filter.filter(rawBpm = 190, timestampMs = 1_000L)).isEqualTo(70)
    }

    @Test
    fun `accepts a fast but physiologically plausible change`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 140, timestampMs = 0L)
        // A 30 bpm surge over one second (hard effort/sprint) is plausible.
        assertThat(filter.filter(rawBpm = 170, timestampMs = 1_000L)).isEqualTo(170)
    }

    @Test
    fun `null reading resets state and returns null`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 145, timestampMs = 0L)
        assertThat(filter.filter(rawBpm = null, timestampMs = 1_000L)).isNull()
        // After the reset, the next reading is accepted outright rather than
        // being compared against the stale 145 baseline.
        assertThat(filter.filter(rawBpm = 200, timestampMs = 2_000L)).isEqualTo(200)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.HeartRateFilterTest"`
Expected: compilation FAILURE — `HeartRateFilter` doesn't exist yet.

- [ ] **Step 3: Implement `HeartRateFilter`**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeartRateFilter.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import kotlin.math.abs

/**
 * Rejects physiologically implausible heart-rate readings before they reach
 * alerts/UI: values outside a plausible human range, and single-notification
 * jumps too large to be a real heartbeat change (a corrupted BLE packet)
 * rather than genuine effort. A rejected reading is dropped — the last
 * accepted value is returned instead of clamping or interpolating a guess.
 */
class HeartRateFilter(
    private val minPlausibleBpm: Int = 30,
    private val maxPlausibleBpm: Int = 220,
    // Generous cap on how fast heart rate can genuinely change between two
    // consecutive BLE notifications (~1/s). Real hard-effort surges are well
    // under this; a corrupted single packet reading a wildly different value
    // is not.
    private val maxChangeBpmPerSecond: Double = 60.0,
) {
    private var lastAcceptedBpm: Int? = null
    private var lastAcceptedAtMs: Long? = null

    /**
     * Filters a raw BPM reading (null when no HR sensor is connected).
     * Returns the accepted value, the last accepted value if this reading is
     * rejected, or null if nothing has ever been accepted.
     */
    fun filter(
        rawBpm: Int?,
        timestampMs: Long,
    ): Int? {
        if (rawBpm == null) {
            reset()
            return null
        }
        if (rawBpm < minPlausibleBpm || rawBpm > maxPlausibleBpm) {
            return lastAcceptedBpm
        }
        val prevBpm = lastAcceptedBpm
        val prevAtMs = lastAcceptedAtMs
        if (prevBpm != null && prevAtMs != null) {
            val elapsedSeconds = (timestampMs - prevAtMs).coerceAtLeast(1L) / 1000.0
            val maxChange = maxChangeBpmPerSecond * elapsedSeconds
            if (abs(rawBpm - prevBpm) > maxChange) {
                return prevBpm
            }
        }
        lastAcceptedBpm = rawBpm
        lastAcceptedAtMs = timestampMs
        return rawBpm
    }

    /** Clears accepted-reading state, e.g. when tracking stops or the sensor disconnects. */
    fun reset() {
        lastAcceptedBpm = null
        lastAcceptedAtMs = null
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.HeartRateFilterTest"`
Expected: BUILD SUCCESSFUL, all 7 tests pass.

- [ ] **Step 5: Write the integration test in `RideTrackerTest.kt`**

Add this test after the two cadence-timeout tests from Task 2:

```kotlin
    @Test
    fun `an implausible heart-rate spike is rejected and the last good value is kept`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 145, connected = true))
            ble.samples.emit(BleSample(timestampMs = 1_000L, heartRateBpm = 255, connected = true))

            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(145)
        }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: FAIL — `tracker.state.value.metrics.heartRateBpm` is `255`, not `145` (nothing filters it yet).

- [ ] **Step 7: Wire `HeartRateFilter` into `RideTracker`**

Add a new constructor parameter right after `routeFollower`:

```kotlin
    private val routeFollower: RouteFollower = RouteFollower(),
    private val heartRateFilter: HeartRateFilter = HeartRateFilter(),
```

In `launchCollection()`'s `bleJob`, replace:

```kotlin
                                            metrics =
                                                current.metrics.copy(
                                                    heartRateBpm = ble.heartRateBpm,
                                                    cadenceRpm = cadenceOrZeroIfStale(ble.cadenceRpm, ble.timestampMs),
                                                ),
```

with:

```kotlin
                                            metrics =
                                                current.metrics.copy(
                                                    heartRateBpm = heartRateFilter.filter(ble.heartRateBpm, ble.timestampMs),
                                                    cadenceRpm = cadenceOrZeroIfStale(ble.cadenceRpm, ble.timestampMs),
                                                ),
```

Reset the filter alongside the other per-session state. In `startNewSession()`, replace:

```kotlin
                lowSpeedSinceMs = null
                lastCadenceUpdateAtMs = null
                resetAlertState()
```

with:

```kotlin
                lowSpeedSinceMs = null
                lastCadenceUpdateAtMs = null
                heartRateFilter.reset()
                resetAlertState()
```

In `stop()`, replace:

```kotlin
        lowSpeedSinceMs = null
        lastCadenceUpdateAtMs = null
        resetAlertState()
```

with:

```kotlin
        lowSpeedSinceMs = null
        lastCadenceUpdateAtMs = null
        heartRateFilter.reset()
        resetAlertState()
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Verify `TrackingDataModule.kt` still compiles unchanged**

Run: `./gradlew :feature:tracking:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (The new `heartRateFilter` parameter is trailing-defaulted after `routeFollower`, so `single { RideTracker(get(), get(), get(), get(), get(), get(), get()) }` needs no change — confirm by reading `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/TrackingDataModule.kt` and checking it's untouched.)

- [ ] **Step 10: Run the full `:core:domain` suite**

Run: `./gradlew :core:domain:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 11: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeartRateFilter.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeartRateFilterTest.kt \
        core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt
git commit -m "$(cat <<'EOF'
Reject physiologically implausible heart-rate readings

Every previously-parsed BPM value — including a 0 or 250+ spike from
a corrupted BLE packet — flowed straight to alerts/UI unvalidated.
HeartRateFilter drops out-of-range values and implausible jumps,
keeping the last good reading instead.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Heart-rate zones (Tanaka %HRmax)

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeartRateZoneCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeartRateZoneCalculatorTest.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/model/RideMetricsUi.kt`
- Test: `feature/dashboard/presentation/src/test/kotlin/com/speedevand/inkride/dashboard/presentation/model/RideMetricsUiTest.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardViewModel.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/InfoBar.kt`
- Modify: `feature/dashboard/presentation/src/main/res/values/strings.xml`
- Modify: `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`

**Interfaces:**
- Produces: `HeartRateZoneCalculator.maxHeartRateBpm(age: Int): Double`, `HeartRateZoneCalculator.zoneFor(heartRateBpm: Int, age: Int): Int` (1–5).
- Produces: `RideMetrics.toRideMetricsUi(units: MeasurementUnits = MeasurementUnits.METRIC, age: Int = 30): RideMetricsUi` (new `age` parameter, defaulted so existing call sites without it keep compiling) and a new `RideMetricsUi.heartRateZone: Int?` field.

- [ ] **Step 1: Write the failing calculator tests**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeartRateZoneCalculatorTest.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class HeartRateZoneCalculatorTest {
    private val calculator = HeartRateZoneCalculator()

    @Test
    fun `maxHeartRateBpm uses the Tanaka formula`() {
        // 208 - 0.7 * 30 = 187
        assertThat(calculator.maxHeartRateBpm(age = 30)).isEqualTo(187.0)
        // 208 - 0.7 * 50 = 173
        assertThat(calculator.maxHeartRateBpm(age = 50)).isEqualTo(173.0)
    }

    @Test
    fun `zone 1 below 60 percent of HRmax`() {
        // HRmax(30) = 187; 59% = 110.33
        assertThat(calculator.zoneFor(heartRateBpm = 110, age = 30)).isEqualTo(1)
    }

    @Test
    fun `zone 2 between 60 and 70 percent`() {
        // 65% of 187 = 121.55
        assertThat(calculator.zoneFor(heartRateBpm = 122, age = 30)).isEqualTo(2)
    }

    @Test
    fun `zone 3 between 70 and 80 percent`() {
        // 75% of 187 = 140.25
        assertThat(calculator.zoneFor(heartRateBpm = 140, age = 30)).isEqualTo(3)
    }

    @Test
    fun `zone 4 between 80 and 90 percent`() {
        // 85% of 187 = 158.95
        assertThat(calculator.zoneFor(heartRateBpm = 159, age = 30)).isEqualTo(4)
    }

    @Test
    fun `zone 5 at or above 90 percent`() {
        // 90% of 187 = 168.3
        assertThat(calculator.zoneFor(heartRateBpm = 169, age = 30)).isEqualTo(5)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.HeartRateZoneCalculatorTest"`
Expected: compilation FAILURE — `HeartRateZoneCalculator` doesn't exist yet.

- [ ] **Step 3: Implement `HeartRateZoneCalculator`**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeartRateZoneCalculator.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

/**
 * Heart-rate training zones derived from age alone (Tanaka formula), so no
 * new user-entered field (e.g. resting heart rate for a Karvonen/HRR
 * calculation) is required. Zone 1 is the lowest-intensity band, zone 5 the
 * highest.
 */
class HeartRateZoneCalculator {
    /** Age-predicted maximum heart rate (Tanaka, 2001): 208 − 0.7 × age. */
    fun maxHeartRateBpm(age: Int): Double = 208.0 - 0.7 * age

    /**
     * Zone (1-5) for [heartRateBpm] given [age], as a percentage-of-HRmax
     * band: Z1 <60%, Z2 60-70%, Z3 70-80%, Z4 80-90%, Z5 >=90%.
     */
    fun zoneFor(
        heartRateBpm: Int,
        age: Int,
    ): Int {
        val hrMax = maxHeartRateBpm(age)
        val percent = heartRateBpm / hrMax * 100.0
        return when {
            percent < 60.0 -> 1
            percent < 70.0 -> 2
            percent < 80.0 -> 3
            percent < 90.0 -> 4
            else -> 5
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.HeartRateZoneCalculatorTest"`
Expected: BUILD SUCCESSFUL, all 6 tests pass.

- [ ] **Step 5: Write the failing `RideMetricsUi` tests**

Add these tests to `feature/dashboard/presentation/src/test/kotlin/com/speedevand/inkride/dashboard/presentation/model/RideMetricsUiTest.kt`, after the existing `null gps accuracy shows poor quality with dash` test:

```kotlin
    @Test
    fun `heart rate produces a zone label when age is known`() {
        val metrics = RideMetrics(heartRateBpm = 150)
        // HRmax(30) = 187; 150/187 = 80.2% -> zone 4.
        val ui = metrics.toRideMetricsUi(age = 30)
        assertThat(ui.heartRateZone).isEqualTo(4)
    }

    @Test
    fun `no heart rate means no zone`() {
        val metrics = RideMetrics(heartRateBpm = null)
        val ui = metrics.toRideMetricsUi()
        assertThat(ui.heartRateZone).isEqualTo(null)
    }
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest --tests "com.speedevand.inkride.dashboard.presentation.model.RideMetricsUiTest"`
Expected: compilation FAILURE — `toRideMetricsUi` has no `age` parameter and `RideMetricsUi` has no `heartRateZone` field.

- [ ] **Step 7: Add `age` and `heartRateZone` to `RideMetricsUi.kt`**

Add the import and a module-level calculator instance, and the new field. Replace:

```kotlin
package com.speedevand.inkride.dashboard.presentation.model

import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.GpsQuality
import com.speedevand.inkride.core.domain.tracking.RideMetrics
import com.speedevand.inkride.core.domain.tracking.WeatherTrend
import com.speedevand.inkride.core.toClockString
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.DISTANCE_ZERO
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.KM_TO_MI_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.M_TO_FT_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.TIME_ZERO
import java.util.Locale

data class RideMetricsUi(
    val currentSpeedKmh: String = "0.0",
    val averageSpeedKmh: String = "0.0",
    val maxSpeedKmh: String = "0.0",
    val distanceKm: String = DISTANCE_ZERO,
    val movingTime: String = TIME_ZERO,
    val elapsedTime: String = TIME_ZERO,
    val altitudeM: String = "--",
    val elevationGainM: String = "0",
    val gradePercent: String = "0.0",
    val caloriesKcal: String = "0",
    val powerWatts: String = "0",
    val gpsAccuracyM: String = "--",
    val bearingDegrees: Float? = null,
    // Null when no BLE sensor of that kind is connected.
    val heartRateBpm: String? = null,
    val cadenceRpm: String? = null,
    // Raw weather trend; the composable maps it to a localized symbol + label.
    val weatherTrend: WeatherTrend = WeatherTrend.UNKNOWN,
    val speedUnit: String = "km/h",
    val distanceUnit: String = "km",
    val altitudeUnit: String = "m",
)

fun RideMetrics.toRideMetricsUi(units: MeasurementUnits = MeasurementUnits.METRIC): RideMetricsUi {
    val speedFactor = if (units == MeasurementUnits.IMPERIAL) KM_TO_MI_FACTOR else 1.0
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) KM_TO_MI_FACTOR else 1.0
    val altitudeFactor = if (units == MeasurementUnits.IMPERIAL) M_TO_FT_FACTOR else 1.0

    return RideMetricsUi(
        currentSpeedKmh = (currentSpeedKmh * speedFactor).format(1),
        averageSpeedKmh = (averageSpeedKmh * speedFactor).format(1),
        maxSpeedKmh = (maxSpeedKmh * speedFactor).format(1),
        distanceKm = (distanceKm * distanceFactor).format(2),
        movingTime = movingTimeSeconds.toClockString(),
        elapsedTime = elapsedTimeSeconds.toClockString(),
        altitudeM = altitudeM?.let { (it * altitudeFactor).format(0) } ?: "--",
        elevationGainM = (elevationGainM * altitudeFactor).format(0),
        gradePercent = gradePercent.format(1),
        caloriesKcal = caloriesKcal.format(0),
        powerWatts = powerWatts.toString(),
        gpsAccuracyM = formatGpsQuality(gpsQuality, gpsAccuracyM?.toDouble(), altitudeFactor),
        bearingDegrees = bearingDegrees,
        heartRateBpm = heartRateBpm?.toString(),
        cadenceRpm = cadenceRpm?.toString(),
        weatherTrend = weatherTrend,
        speedUnit = if (units == MeasurementUnits.IMPERIAL) "mph" else "km/h",
        distanceUnit = if (units == MeasurementUnits.IMPERIAL) "mi" else "km",
        altitudeUnit = if (units == MeasurementUnits.IMPERIAL) "ft" else "m",
    )
}
```

with:

```kotlin
package com.speedevand.inkride.dashboard.presentation.model

import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.GpsQuality
import com.speedevand.inkride.core.domain.tracking.HeartRateZoneCalculator
import com.speedevand.inkride.core.domain.tracking.RideMetrics
import com.speedevand.inkride.core.domain.tracking.WeatherTrend
import com.speedevand.inkride.core.toClockString
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.DISTANCE_ZERO
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.KM_TO_MI_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.M_TO_FT_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.TIME_ZERO
import java.util.Locale

private val heartRateZoneCalculator = HeartRateZoneCalculator()

data class RideMetricsUi(
    val currentSpeedKmh: String = "0.0",
    val averageSpeedKmh: String = "0.0",
    val maxSpeedKmh: String = "0.0",
    val distanceKm: String = DISTANCE_ZERO,
    val movingTime: String = TIME_ZERO,
    val elapsedTime: String = TIME_ZERO,
    val altitudeM: String = "--",
    val elevationGainM: String = "0",
    val gradePercent: String = "0.0",
    val caloriesKcal: String = "0",
    val powerWatts: String = "0",
    val gpsAccuracyM: String = "--",
    val bearingDegrees: Float? = null,
    // Null when no BLE sensor of that kind is connected.
    val heartRateBpm: String? = null,
    // Null whenever heartRateBpm is null; otherwise the rider's current
    // training zone (1-5) from HeartRateZoneCalculator.
    val heartRateZone: Int? = null,
    val cadenceRpm: String? = null,
    // Raw weather trend; the composable maps it to a localized symbol + label.
    val weatherTrend: WeatherTrend = WeatherTrend.UNKNOWN,
    val speedUnit: String = "km/h",
    val distanceUnit: String = "km",
    val altitudeUnit: String = "m",
)

fun RideMetrics.toRideMetricsUi(
    units: MeasurementUnits = MeasurementUnits.METRIC,
    age: Int = 30,
): RideMetricsUi {
    val speedFactor = if (units == MeasurementUnits.IMPERIAL) KM_TO_MI_FACTOR else 1.0
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) KM_TO_MI_FACTOR else 1.0
    val altitudeFactor = if (units == MeasurementUnits.IMPERIAL) M_TO_FT_FACTOR else 1.0

    return RideMetricsUi(
        currentSpeedKmh = (currentSpeedKmh * speedFactor).format(1),
        averageSpeedKmh = (averageSpeedKmh * speedFactor).format(1),
        maxSpeedKmh = (maxSpeedKmh * speedFactor).format(1),
        distanceKm = (distanceKm * distanceFactor).format(2),
        movingTime = movingTimeSeconds.toClockString(),
        elapsedTime = elapsedTimeSeconds.toClockString(),
        altitudeM = altitudeM?.let { (it * altitudeFactor).format(0) } ?: "--",
        elevationGainM = (elevationGainM * altitudeFactor).format(0),
        gradePercent = gradePercent.format(1),
        caloriesKcal = caloriesKcal.format(0),
        powerWatts = powerWatts.toString(),
        gpsAccuracyM = formatGpsQuality(gpsQuality, gpsAccuracyM?.toDouble(), altitudeFactor),
        bearingDegrees = bearingDegrees,
        heartRateBpm = heartRateBpm?.toString(),
        heartRateZone = heartRateBpm?.let { heartRateZoneCalculator.zoneFor(it, age) },
        cadenceRpm = cadenceRpm?.toString(),
        weatherTrend = weatherTrend,
        speedUnit = if (units == MeasurementUnits.IMPERIAL) "mph" else "km/h",
        distanceUnit = if (units == MeasurementUnits.IMPERIAL) "mi" else "km",
        altitudeUnit = if (units == MeasurementUnits.IMPERIAL) "ft" else "m",
    )
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest --tests "com.speedevand.inkride.dashboard.presentation.model.RideMetricsUiTest"`
Expected: BUILD SUCCESSFUL, all tests pass (existing ones unaffected since `age` defaults to 30 and `heartRateZone` is a new, previously-nonexistent field).

- [ ] **Step 9: Pass the rider's age through from `DashboardViewModel`**

In `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardViewModel.kt`, replace:

```kotlin
                DashboardState(
                    rideMetrics = tracking.metrics.toRideMetricsUi(settings.units),
```

with:

```kotlin
                DashboardState(
                    rideMetrics = tracking.metrics.toRideMetricsUi(settings.units, settings.age),
```

- [ ] **Step 10: Add the zone-aware string resource**

In `feature/dashboard/presentation/src/main/res/values/strings.xml`, replace:

```xml
    <string name="dashboard_heart_rate">%1$s bpm</string>
```

with:

```xml
    <string name="dashboard_heart_rate">%1$s bpm</string>
    <string name="dashboard_heart_rate_zone">%1$s bpm (Z%2$d)</string>
```

In `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`, replace:

```xml
    <string name="dashboard_heart_rate">%1$s bpm</string>
```

with:

```xml
    <string name="dashboard_heart_rate">%1$s bpm</string>
    <string name="dashboard_heart_rate_zone">%1$s bpm (Z%2$d)</string>
```

- [ ] **Step 11: Show the zone in `InfoBar.kt`**

Replace:

```kotlin
        metrics.heartRateBpm?.let {
            TextMMD(
                text = stringResource(R.string.dashboard_heart_rate, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
```

with:

```kotlin
        metrics.heartRateBpm?.let { bpm ->
            val heartRateText =
                metrics.heartRateZone?.let { zone ->
                    stringResource(R.string.dashboard_heart_rate_zone, bpm, zone)
                } ?: stringResource(R.string.dashboard_heart_rate, bpm)
            TextMMD(
                text = heartRateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
```

- [ ] **Step 12: Run the full dashboard presentation test suite**

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Compile-check the whole app**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 14: Manual on-device verification**

Pair a BLE HR sensor (or use any BLE HR simulator app), start a ride, and confirm the info bar shows e.g. "142 bpm (Z3)" and the zone changes appropriately as the displayed BPM crosses a zone boundary for the configured age (Settings → Profile → Age).

- [ ] **Step 15: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeartRateZoneCalculator.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeartRateZoneCalculatorTest.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/model/RideMetricsUi.kt \
        feature/dashboard/presentation/src/test/kotlin/com/speedevand/inkride/dashboard/presentation/model/RideMetricsUiTest.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardViewModel.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/InfoBar.kt \
        feature/dashboard/presentation/src/main/res/values/strings.xml \
        feature/dashboard/presentation/src/main/res/values-pl/strings.xml
git commit -m "$(cat <<'EOF'
Show a Tanaka %HRmax training zone alongside live heart rate

Uses the rider's existing age field (no new settings/migration
needed) instead of a Karvonen/HRR calculation, which would require
collecting a resting heart rate the app doesn't have today.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Calories — document the decision not to add power-based calories

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/CaloriesEstimator.kt`

**Interfaces:** none — documentation only, no behavior change.

- [ ] **Step 1: Add the rationale to the class KDoc**

In `CaloriesEstimator.kt`, replace:

```kotlin
     * Grade awareness: riding uphill requires significantly more energy;
     * downhill provides less braking-resistance credit (floor at 2 MET coasting).
     *
     * @param speedKmh current speed in km/h
```

with:

```kotlin
     * Grade awareness: riding uphill requires significantly more energy;
     * downhill provides less braking-resistance credit (floor at 2 MET coasting).
     *
     * A power-based alternative (kcal derived from [PowerEstimator]'s
     * estimated watts) was considered and deliberately rejected: PowerEstimator
     * itself carries a documented ±30-60% accuracy ceiling (dominated by
     * unmeasured wind), so deriving calories from it would compound one
     * uncertain estimate on top of another rather than improve on this
     * MET-based model, which only requires speed/grade/weight/age.
     *
     * @param speedKmh current speed in km/h
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :core:domain:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/CaloriesEstimator.kt
git commit -m "$(cat <<'EOF'
Document the decision not to derive calories from estimated power

Recorded so a future audit doesn't re-flag this as an oversight:
PowerEstimator's own ±30-60% accuracy ceiling would compound onto
the calorie figure rather than improve it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Altitude-adjusted air density in `PowerEstimator`

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PowerEstimator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/PowerEstimatorTest.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt` (single call-site change only — do not touch anything else in this file, and do not modify `RideMetricsCalculatorTest.kt`)

**Interfaces:**
- Produces: `PowerEstimator.estimateWatts(speedMps: Double, accelerationMps2: Double, gradePercent: Double, userSettings: UserSettings, altitudeM: Double? = null): Int` — `altitudeM` is a new trailing-defaulted parameter, so existing 4-positional-argument call sites keep compiling.

- [ ] **Step 1: Write the failing tests**

Add these tests to `PowerEstimatorTest.kt`, after the existing `flat ground produces no gravity component` test:

```kotlin
    @Test
    fun `higher altitude reduces air-density-driven power at the same speed and grade`() {
        val seaLevelWatts = estimator.estimateWatts(8.0, 0.0, 0.0, defaultSettings, altitudeM = 0.0)
        val highAltitudeWatts = estimator.estimateWatts(8.0, 0.0, 0.0, defaultSettings, altitudeM = 3_000.0)
        assertThat(highAltitudeWatts).isLessThan(seaLevelWatts)
    }

    @Test
    fun `null altitude falls back to sea-level air density`() {
        val nullAltitudeWatts = estimator.estimateWatts(8.0, 0.0, 0.0, defaultSettings, altitudeM = null)
        val explicitSeaLevelWatts = estimator.estimateWatts(8.0, 0.0, 0.0, defaultSettings, altitudeM = 0.0)
        assertThat(nullAltitudeWatts).isEqualTo(explicitSeaLevelWatts)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.PowerEstimatorTest"`
Expected: compilation FAILURE — `estimateWatts` has no `altitudeM` parameter.

- [ ] **Step 3: Add altitude-adjusted air density to `PowerEstimator`**

Replace:

```kotlin
    fun estimateWatts(
        speedMps: Double,
        accelerationMps2: Double,
        gradePercent: Double,
        userSettings: UserSettings,
    ): Int {
        if (speedMps <= 0.1) return 0

        val totalMassKg = userSettings.weightKg + userSettings.bikeWeightKg
        val gravity = 9.81
        val airDensity = 1.225 // kg/m³ at sea level, 15°C
```

with:

```kotlin
    fun estimateWatts(
        speedMps: Double,
        accelerationMps2: Double,
        gradePercent: Double,
        userSettings: UserSettings,
        altitudeM: Double? = null,
    ): Int {
        if (speedMps <= 0.1) return 0

        val totalMassKg = userSettings.weightKg + userSettings.bikeWeightKg
        val gravity = 9.81
        val airDensity = airDensityAt(altitudeM)
```

Add the new private function and import, right after the class's `estimateWatts` function and before the `companion object`:

```kotlin
    /**
     * Air density (kg/m³) at [altitudeM] using the ISA approximation, falling
     * back to sea-level density when altitude is unknown (e.g. the first few
     * samples of a ride, before barometer/GPS altitude has been established).
     */
    private fun airDensityAt(altitudeM: Double?): Double {
        val seaLevelDensityKgM3 = 1.225
        if (altitudeM == null) return seaLevelDensityKgM3
        return seaLevelDensityKgM3 * (1.0 - 2.25577e-5 * altitudeM).coerceAtLeast(0.0).pow(5.25588)
    }
```

Add the import at the top of the file:

```kotlin
import kotlin.math.pow
```

(alongside the existing `kotlin.math.atan`, `kotlin.math.cos`, `kotlin.math.sin` imports).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.PowerEstimatorTest"`
Expected: BUILD SUCCESSFUL, all tests pass (existing tests unaffected — they don't pass `altitudeM`, so it defaults to `null`, which resolves to the same sea-level density as before).

- [ ] **Step 5: Thread the calculator's fused altitude into the power call**

In `RideMetricsCalculator.kt`, replace:

```kotlin
                currentPowerWatts =
                    powerEstimator.estimateWatts(
                        speedMps = speedMps,
                        accelerationMps2 = smoothedAccelMps2,
                        gradePercent = currentGrade,
                        userSettings = userSettings,
                    )
```

with:

```kotlin
                currentPowerWatts =
                    powerEstimator.estimateWatts(
                        speedMps = speedMps,
                        accelerationMps2 = smoothedAccelMps2,
                        gradePercent = currentGrade,
                        userSettings = userSettings,
                        altitudeM = smoothedAltitudeM,
                    )
```

Do not modify anything else in this file.

- [ ] **Step 6: Run the full `:core:domain` test suite to confirm `RideMetricsCalculatorTest` is unaffected**

Run: `./gradlew :core:domain:test`
Expected: BUILD SUCCESSFUL, including every existing `RideMetricsCalculatorTest` case unchanged (none of them assert on `powerWatts`/`averagePowerWatts` values sensitive to air density at the altitudes those tests use, so this is a behavior-preserving change for them).

- [ ] **Step 7: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PowerEstimator.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/PowerEstimatorTest.kt \
        core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt
git commit -m "$(cat <<'EOF'
Adjust PowerEstimator's air density for altitude

Air density drops measurably on sustained climbs, reducing aero
drag; PowerEstimator previously assumed sea level everywhere. Uses
the ISA approximation fed by the already-computed fused altitude.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `PositionKalmanFilter` — pure Kalman filter for GPS position

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PositionKalmanFilter.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/PositionKalmanFilterTest.kt`

**Interfaces:**
- Produces: `FilteredPosition(latitude: Double, longitude: Double, speedMps: Double, bearingDegrees: Float?, wasGated: Boolean)`, `PositionKalmanFilter.update(latitude: Double, longitude: Double, accuracyM: Float, timestampMs: Long): FilteredPosition`, `PositionKalmanFilter.reset()`.
- This class has no dependency on `RideMetricsCalculator`, `RideSensorSample`, or anything Android — it is consumed in Task 8.

- [ ] **Step 1: Write the failing tests**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/PositionKalmanFilterTest.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import kotlin.math.cos
import org.junit.jupiter.api.Test

class PositionKalmanFilterTest {
    @Test
    fun `first fix passes through unchanged with zero speed`() {
        val filter = PositionKalmanFilter()
        val result = filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        assertThat(result.latitude).isEqualTo(50.0)
        assertThat(result.longitude).isEqualTo(19.0)
        assertThat(result.speedMps).isEqualTo(0.0)
        assertThat(result.wasGated).isFalse()
    }

    @Test
    fun `a fix within the accuracy noise floor is blended in, not gated`() {
        val filter = PositionKalmanFilter()
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 1_000L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 2_000L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 3_000L)

        // ~5m north of the true (converged) point — within the reported
        // accuracy, not an outlier.
        val noisyLat = 50.0 + 5.0 / 111_320.0
        val result = filter.update(latitude = noisyLat, longitude = 19.0, accuracyM = 5.0f, timestampMs = 4_000L)

        assertThat(result.wasGated).isFalse()
        // Blended toward, but not all the way to, the noisy 5m offset —
        // mathematically guaranteed for any valid Kalman gain in (0, 1) when
        // the pre-update predicted position exactly matches the reference
        // point, as it does here after four identical prior fixes.
        val filteredOffsetM = (result.latitude - 50.0) * 111_320.0
        assertThat(filteredOffsetM).isGreaterThan(0.0)
        assertThat(filteredOffsetM).isLessThan(5.0)
    }

    @Test
    fun `a wildly displaced fix is gated and the filter dead-reckons instead`() {
        val filter = PositionKalmanFilter()
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 1_000L)
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 2_000L)

        // 200m north in one second — a GPS glitch, not real movement at rest.
        val wildLat = 50.0 + 200.0 / 111_320.0
        val result = filter.update(latitude = wildLat, longitude = 19.0, accuracyM = 5.0f, timestampMs = 3_000L)

        assertThat(result.wasGated).isTrue()
        // Dead-reckoned from ~zero velocity, so it stays far from the wild fix.
        val distanceFromWildFixM = (wildLat - result.latitude) * 111_320.0
        assertThat(distanceFromWildFixM).isGreaterThan(100.0)
    }

    @Test
    fun `velocity direction follows the rider through a turn from east to north`() {
        val filter = PositionKalmanFilter()
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(50.0))
        val metersPerDegLat = 111_320.0

        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)

        // Ride east at ~5 m/s for 10 seconds.
        lateinit var eastResult: FilteredPosition
        for (step in 1..10) {
            val lon = 19.0 + (step * 5.0) / metersPerDegLon
            eastResult = filter.update(latitude = 50.0, longitude = lon, accuracyM = 5.0f, timestampMs = step * 1_000L)
        }
        // Bearing near 90° (east); generous tolerance for filter convergence.
        assertThat(eastResult.bearingDegrees!!).isGreaterThan(45f)
        assertThat(eastResult.bearingDegrees!!).isLessThan(135f)

        // Turn: ride north at ~5 m/s for 10 more seconds from the current longitude.
        val turnLon = 19.0 + (10 * 5.0) / metersPerDegLon
        lateinit var northResult: FilteredPosition
        for (step in 1..10) {
            val lat = 50.0 + (step * 5.0) / metersPerDegLat
            northResult = filter.update(latitude = lat, longitude = turnLon, accuracyM = 5.0f, timestampMs = (10 + step) * 1_000L)
        }
        // Bearing near 0°/360° (north) after the turn.
        val bearing = northResult.bearingDegrees!!
        assertThat(bearing < 60f || bearing > 300f).isTrue()
    }

    @Test
    fun `reset clears state so the next fix passes through unchanged again`() {
        val filter = PositionKalmanFilter()
        filter.update(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, timestampMs = 0L)
        filter.update(latitude = 50.0001, longitude = 19.0001, accuracyM = 5.0f, timestampMs = 1_000L)
        filter.reset()

        val result = filter.update(latitude = 51.0, longitude = 20.0, accuracyM = 5.0f, timestampMs = 5_000L)
        assertThat(result.latitude).isEqualTo(51.0)
        assertThat(result.longitude).isEqualTo(20.0)
        assertThat(result.speedMps).isEqualTo(0.0)
    }
}
```

Note for the implementer: the "turn" test depends on the filter tracking velocity within 10 samples of consistent movement, which is a qualitative convergence property, not an exact number. If it fails with the bearing just outside the tolerance, widen the bounds (e.g. 30°/150° and 70°/290°) rather than treating it as evidence the filter's core math is wrong — re-check Step 3's matrix arithmetic against the derivation in this task first.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.PositionKalmanFilterTest"`
Expected: compilation FAILURE — `PositionKalmanFilter` doesn't exist yet.

- [ ] **Step 3: Implement `PositionKalmanFilter`**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PositionKalmanFilter.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Result of feeding one raw GPS fix into [PositionKalmanFilter]: the filtered
 * position and the velocity-derived speed/bearing. On a gated (rejected) fix,
 * this is a dead-reckoned prediction from the last known velocity, not the
 * raw (likely erroneous) input.
 */
data class FilteredPosition(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val bearingDegrees: Float?,
    // True when this fix's raw position was statistically implausible given
    // the filter's current velocity/uncertainty estimate and was replaced by
    // a dead-reckoned prediction instead of being incorporated.
    val wasGated: Boolean,
)

/**
 * A constant-velocity Kalman filter that smooths raw GPS fixes before they
 * reach [RideMetricsCalculator]. Runs in a local equirectangular (east/north,
 * meters) tangent plane anchored at the first accepted fix, since lat/lon
 * degrees are not uniform distance units.
 *
 * State vector: [eastM, northM, velEastMps, velNorthMps]. Measurement noise
 * is derived per-fix from the reported GPS accuracy (variance = accuracy²),
 * so a tight fix is trusted more than a loose one. A fix whose innovation
 * (difference between predicted and measured position) is statistically
 * implausible — beyond [gateChiSquareThreshold], the ~99% bound for 2 degrees
 * of freedom — is gated: the filter predicts through it via dead-reckoning
 * instead of incorporating the measurement, the formal analogue of
 * [RideMetricsCalculator]'s ad-hoc bounce/jump detection.
 *
 * This class knows nothing about [RideMetricsCalculator] or vice versa — it's
 * consumed one layer up, in the Android-facing sensor data source, before a
 * [RideSensorSample] is built. That keeps [RideMetricsCalculator]'s existing
 * test suite completely unaffected by this filter's introduction.
 */
class PositionKalmanFilter(
    // Process noise spectral density ((m/s²)² per second) — how much the
    // filter expects true velocity to vary between fixes. Higher trusts new
    // measurements more; lower trusts the constant-velocity prediction more.
    // 3.0 is a moderate middle ground for cycling's typical accelerations.
    private val processNoiseDensity: Double = 3.0,
    // Chi-square bound for 2 degrees of freedom at ~99% confidence — a fix
    // whose innovation exceeds this is treated as an outlier.
    private val gateChiSquareThreshold: Double = 9.21,
    // Floor on the per-fix measurement-noise standard deviation (meters), so
    // a GPS accuracy reading of 0 can't collapse the filter onto a single
    // point with false certainty.
    private val minAccuracyM: Double = 2.0,
    // Upper bound on the elapsed time between fixes used for prediction, so a
    // fix returning after a long GPS dropout doesn't blow up the process-noise
    // terms (which scale with dt^3/dt^4).
    private val maxDtSeconds: Double = 10.0,
) {
    private var originLatDeg: Double? = null
    private var originLonDeg: Double? = null
    private var metersPerDegLat: Double = 0.0
    private var metersPerDegLon: Double = 0.0

    // State: [eastM, northM, velEastMps, velNorthMps].
    private var state: DoubleArray? = null

    // 4x4 state covariance.
    private var covariance: Array<DoubleArray>? = null
    private var lastTimestampMs: Long? = null

    /**
     * Feeds one raw GPS fix (already passed the source's freshness/accuracy
     * gate). [accuracyM] is the fix's reported horizontal accuracy in meters.
     */
    fun update(
        latitude: Double,
        longitude: Double,
        accuracyM: Float,
        timestampMs: Long,
    ): FilteredPosition {
        val prevState = state
        val prevCovariance = covariance
        val prevTimestampMs = lastTimestampMs

        if (prevState == null || prevCovariance == null || prevTimestampMs == null) {
            originLatDeg = latitude
            originLonDeg = longitude
            metersPerDegLat = 111_320.0
            metersPerDegLon = 111_320.0 * cos(Math.toRadians(latitude))
            state = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            val initialPositionVarianceM2 = accuracyVariance(accuracyM)
            covariance =
                arrayOf(
                    doubleArrayOf(initialPositionVarianceM2, 0.0, 0.0, 0.0),
                    doubleArrayOf(0.0, initialPositionVarianceM2, 0.0, 0.0),
                    doubleArrayOf(0.0, 0.0, 25.0, 0.0),
                    doubleArrayOf(0.0, 0.0, 0.0, 25.0),
                )
            lastTimestampMs = timestampMs
            return FilteredPosition(latitude, longitude, speedMps = 0.0, bearingDegrees = null, wasGated = false)
        }

        val dtSeconds = (((timestampMs - prevTimestampMs).coerceAtLeast(0L)) / 1000.0).coerceAtMost(maxDtSeconds)

        // Predict.
        val f = transitionMatrix(dtSeconds)
        val predictedState = matVecMul(f, prevState)
        val q = processNoiseMatrix(dtSeconds)
        val predictedCovariance = matAdd(matMul(matMul(f, prevCovariance), matTranspose(f)), q)

        // Measurement: this fix's position in the local ENU plane.
        val measuredEastM = (longitude - originLonDeg!!) * metersPerDegLon
        val measuredNorthM = (latitude - originLatDeg!!) * metersPerDegLat
        val positionVarianceM2 = accuracyVariance(accuracyM)

        // Innovation (2D: east, north).
        val innovationEast = measuredEastM - predictedState[0]
        val innovationNorth = measuredNorthM - predictedState[1]

        // Innovation covariance S = H P' H^T + R, restricted to the position
        // block of the covariance (H selects [eastM, northM]).
        val sEE = predictedCovariance[0][0] + positionVarianceM2
        val sEN = predictedCovariance[0][1]
        val sNE = predictedCovariance[1][0]
        val sNN = predictedCovariance[1][1] + positionVarianceM2

        val det = sEE * sNN - sEN * sNE
        val safeDet = if (det == 0.0) 1e-9 else det
        val sInvEE = sNN / safeDet
        val sInvEN = -sEN / safeDet
        val sInvNE = -sNE / safeDet
        val sInvNN = sEE / safeDet

        val mahalanobisSquared =
            innovationEast * (sInvEE * innovationEast + sInvEN * innovationNorth) +
                innovationNorth * (sInvNE * innovationEast + sInvNN * innovationNorth)

        val isGated = mahalanobisSquared > gateChiSquareThreshold

        val (finalState, finalCovariance) =
            if (isGated) {
                predictedState to predictedCovariance
            } else {
                // Kalman gain K = P' H^T S^-1. H only selects the first two
                // state components, so P' H^T reduces to the first two
                // columns of P'.
                val gain = Array(4) { DoubleArray(2) }
                for (row in 0 until 4) {
                    val pRowEast = predictedCovariance[row][0]
                    val pRowNorth = predictedCovariance[row][1]
                    gain[row][0] = pRowEast * sInvEE + pRowNorth * sInvNE
                    gain[row][1] = pRowEast * sInvEN + pRowNorth * sInvNN
                }
                val updatedState = DoubleArray(4)
                for (row in 0 until 4) {
                    updatedState[row] =
                        predictedState[row] + gain[row][0] * innovationEast + gain[row][1] * innovationNorth
                }
                // P = (I - K H) P' — K H's only nonzero columns are 0 and 1.
                val updatedCovariance = Array(4) { DoubleArray(4) }
                for (row in 0 until 4) {
                    for (col in 0 until 4) {
                        val khTerm = gain[row][0] * predictedCovariance[0][col] + gain[row][1] * predictedCovariance[1][col]
                        updatedCovariance[row][col] = predictedCovariance[row][col] - khTerm
                    }
                }
                updatedState to updatedCovariance
            }

        state = finalState
        covariance = finalCovariance
        lastTimestampMs = timestampMs

        val filteredLat = originLatDeg!! + finalState[1] / metersPerDegLat
        val filteredLon = originLonDeg!! + finalState[0] / metersPerDegLon
        val velEast = finalState[2]
        val velNorth = finalState[3]
        val speedMps = sqrt(velEast * velEast + velNorth * velNorth)
        val bearingDegrees =
            if (speedMps > 0.1) {
                ((Math.toDegrees(atan2(velEast, velNorth)) + 360.0) % 360.0).toFloat()
            } else {
                null
            }

        return FilteredPosition(filteredLat, filteredLon, speedMps, bearingDegrees, isGated)
    }

    /** Clears all filter state, e.g. when a ride stops or GPS is restarted. */
    fun reset() {
        originLatDeg = null
        originLonDeg = null
        metersPerDegLat = 0.0
        metersPerDegLon = 0.0
        state = null
        covariance = null
        lastTimestampMs = null
    }

    private fun accuracyVariance(accuracyM: Float): Double {
        val clamped = max(accuracyM.toDouble(), minAccuracyM)
        return clamped * clamped
    }

    private fun transitionMatrix(dtSeconds: Double): Array<DoubleArray> =
        arrayOf(
            doubleArrayOf(1.0, 0.0, dtSeconds, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, dtSeconds),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0),
        )

    /** Discretized white-noise-acceleration process noise for a constant-velocity model. */
    private fun processNoiseMatrix(dtSeconds: Double): Array<DoubleArray> {
        val dt2 = dtSeconds * dtSeconds
        val dt3 = dt2 * dtSeconds
        val dt4 = dt3 * dtSeconds
        val q = processNoiseDensity
        return arrayOf(
            doubleArrayOf(q * dt4 / 4.0, 0.0, q * dt3 / 2.0, 0.0),
            doubleArrayOf(0.0, q * dt4 / 4.0, 0.0, q * dt3 / 2.0),
            doubleArrayOf(q * dt3 / 2.0, 0.0, q * dt2, 0.0),
            doubleArrayOf(0.0, q * dt3 / 2.0, 0.0, q * dt2),
        )
    }

    private fun matVecMul(
        m: Array<DoubleArray>,
        v: DoubleArray,
    ): DoubleArray =
        DoubleArray(m.size) { row ->
            var sum = 0.0
            for (col in v.indices) sum += m[row][col] * v[col]
            sum
        }

    private fun matMul(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>,
    ): Array<DoubleArray> {
        val rows = a.size
        val cols = b[0].size
        val inner = b.size
        return Array(rows) { row ->
            DoubleArray(cols) { col ->
                var sum = 0.0
                for (k in 0 until inner) sum += a[row][k] * b[k][col]
                sum
            }
        }
    }

    private fun matTranspose(m: Array<DoubleArray>): Array<DoubleArray> {
        val rows = m.size
        val cols = m[0].size
        return Array(cols) { col -> DoubleArray(rows) { row -> m[row][col] } }
    }

    private fun matAdd(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>,
    ): Array<DoubleArray> = Array(a.size) { row -> DoubleArray(a[row].size) { col -> a[row][col] + b[row][col] } }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.PositionKalmanFilterTest"`
Expected: BUILD SUCCESSFUL, all 5 tests pass. If only the turn-tracking test fails on tolerance, widen its bounds per the note in Step 1 and re-run before concluding anything is wrong with the implementation.

- [ ] **Step 5: Run the full `:core:domain` suite to confirm no regressions**

Run: `./gradlew :core:domain:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PositionKalmanFilter.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/PositionKalmanFilterTest.kt
git commit -m "$(cat <<'EOF'
Add a Kalman filter for smoothing raw GPS position

Constant-velocity filter in a local ENU tangent plane, with
per-fix measurement noise from GPS accuracy and Mahalanobis
gating as a statistically principled analogue of the ad-hoc
bounce/jump detection already in RideMetricsCalculator. Not
wired in yet — that's Task 8.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Wire `PositionKalmanFilter` into `AndroidRideSensorDataSource`

**Files:**
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt`

**Interfaces:**
- Consumes: `PositionKalmanFilter.update(latitude, longitude, accuracyM, timestampMs): FilteredPosition` and `.reset()` from Task 7.
- Produces: no change to any public interface — `RideSensorSample.latitude`/`longitude` are now Kalman-filtered instead of raw, everything else (`altitudeFromGpsM`, `speedFromGpsMps`, `accuracyM`, `bearingDegrees`, `satelliteCount`) is unchanged so the existing GPS-Doppler-vs-distance cross-validation in `RideMetricsCalculator` keeps comparing the (now smoother) distance-implied speed against the untouched raw Doppler speed.

No automated test exists for this class (same rationale as Task 1); `PositionKalmanFilter`'s own math is already fully covered by Task 7's tests. This task is verified by compilation, the full `:core:domain` suite (to confirm `RideMetricsCalculatorTest` truly is unaffected end-to-end), and manual on-device verification.

- [ ] **Step 1: Add the filter field and import**

Add the import next to the other `com.speedevand.inkride.core.domain.tracking.*` imports:

```kotlin
import com.speedevand.inkride.core.domain.tracking.PositionKalmanFilter
```

Add the field next to `headingSmoother`:

```kotlin
    private val headingSmoother = HeadingSmoother()

    private val positionKalmanFilter = PositionKalmanFilter()

```

- [ ] **Step 2: Filter the position in `emitSample()`**

Replace:

```kotlin
        // Use the most recent sensor timestamp to avoid stamping
        // barometer/heading data with an old GPS timestamp or vice versa.
        val sampleTimestampMs =
            maxOf(
                lastGpsTimestampMs,
                lastPressureTimestampMs,
                lastHeadingTimestampMs,
                now, // fallback
            )

        // Bearing source: while moving, GPS course-over-ground is far more
```

with:

```kotlin
        // Use the most recent sensor timestamp to avoid stamping
        // barometer/heading data with an old GPS timestamp or vice versa.
        val sampleTimestampMs =
            maxOf(
                lastGpsTimestampMs,
                lastPressureTimestampMs,
                lastHeadingTimestampMs,
                now, // fallback
            )

        // Smooth the raw fix through the Kalman filter before it becomes this
        // sample's position — RideMetricsCalculator's distance/speed/outlier
        // logic then operates on the filtered position exactly as it did on
        // the raw one. speedFromGpsMps (the chipset's own Doppler estimate)
        // is left untouched, so RideMetricsCalculator's existing GPS-vs-
        // distance cross-validation still compares two independent signals.
        val filteredPosition =
            if (useGpsData) {
                positionKalmanFilter.update(
                    latitude = location!!.latitude,
                    longitude = location.longitude,
                    accuracyM = location.accuracy,
                    timestampMs = sampleTimestampMs,
                )
            } else {
                null
            }

        // Bearing source: while moving, GPS course-over-ground is far more
```

Replace:

```kotlin
        samplesFlow.tryEmit(
            RideSensorSample(
                timestampMs = sampleTimestampMs,
                latitude = if (useGpsData) location.latitude else null,
                longitude = if (useGpsData) location.longitude else null,
                altitudeFromGpsM = if (useGpsData) location.let { if (it.hasAltitude()) it.altitude else null } else null,
                altitudeFromBarometerM = altitudeFromBarometer,
                speedFromGpsMps = if (useGpsData) location.let { if (it.hasSpeed()) it.speed.toDouble() else null } else null,
                accuracyM = if (useGpsData) location.let { if (it.hasAccuracy()) it.accuracy else null } else null,
                bearingDegrees = bearing,
                satelliteCount = if (useGpsData) lastSatelliteCount else null,
                pressureHpa = pressureHpa?.toDouble(),
            ),
        )
```

with:

```kotlin
        samplesFlow.tryEmit(
            RideSensorSample(
                timestampMs = sampleTimestampMs,
                latitude = filteredPosition?.latitude,
                longitude = filteredPosition?.longitude,
                altitudeFromGpsM = if (useGpsData) location.let { if (it.hasAltitude()) it.altitude else null } else null,
                altitudeFromBarometerM = altitudeFromBarometer,
                speedFromGpsMps = if (useGpsData) location.let { if (it.hasSpeed()) it.speed.toDouble() else null } else null,
                accuracyM = if (useGpsData) location.let { if (it.hasAccuracy()) it.accuracy else null } else null,
                bearingDegrees = bearing,
                satelliteCount = if (useGpsData) lastSatelliteCount else null,
                pressureHpa = pressureHpa?.toDouble(),
            ),
        )
```

- [ ] **Step 3: Reset the filter in `stop()`**

Replace:

```kotlin
        lastLocation = null
        lastPressureHpa = null
        lastHeading = null
        headingSmoother.reset()
```

with:

```kotlin
        lastLocation = null
        lastPressureHpa = null
        lastHeading = null
        headingSmoother.reset()
        positionKalmanFilter.reset()
```

- [ ] **Step 4: Compile-check**

Run: `./gradlew :feature:tracking:data:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full `:core:domain` suite to confirm `RideMetricsCalculatorTest` is unaffected**

Run: `./gradlew :core:domain:test`
Expected: BUILD SUCCESSFUL. (This module doesn't depend on `:feature:tracking:data`, so this specifically confirms Task 7's filter and `RideMetricsCalculator` remain independently correct; it does not exercise this task's wiring — that's Step 6.)

- [ ] **Step 6: Manual on-device verification**

Install the debug build, start a ride outdoors, and confirm: distance/speed on the dashboard behave sensibly (no sudden spikes from GPS glitches near buildings/tunnels), and that a stationary period (e.g. waiting at a light) doesn't accumulate phantom distance. Compare a short ride's total distance against a known reference (e.g. a fixed route) to sanity-check it isn't wildly off from before this change.

- [ ] **Step 7: Commit**

```bash
git add feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt
git commit -m "$(cat <<'EOF'
Smooth raw GPS position through PositionKalmanFilter before emitting

Wires Task 7's filter into the data-source layer, one step before
RideMetricsCalculator — its distance/speed/outlier-rejection logic
now runs on Kalman-filtered positions as a secondary safety net, with
its own test suite completely untouched.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Rollout

Tasks 1–8 are independent (Task 8 depends only on Task 7 landing first) and can be executed and reviewed one at a time, each ending in its own commit — consistent with how the design doc's seven items were scoped. No new permissions, no database migrations, no new `UserSettings` fields anywhere in this plan.
