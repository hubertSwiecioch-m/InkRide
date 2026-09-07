# Sensor-Adapter Fusion Test Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `AndroidRideSensorDataSource`'s GPS/barometer/heading fusion logic (currently zero test coverage) direct, fast, deterministic unit and Robolectric-integration test coverage, fixing any correctness bugs the new tests reveal.

**Architecture:** Extract `emitSample()`'s pure fusion/gating decision logic into a new framework-free `RideSampleAssembler` class in `core:domain:tracking` (wrapping the existing `PositionKalmanFilter`), unit test it exhaustively with no Robolectric needed, then refactor `AndroidRideSensorDataSource` to delegate to it. Separately add Robolectric-based tests in `feature:tracking:data` for `start()`'s permission/hardware gating and one integration test that drives a real `Location` fix through the actual class end-to-end via `ShadowLocationManager.simulateLocation`.

**Tech Stack:** Kotlin, JUnit5 + assertk (already wired by the `inkride.kotlin.library`/`inkride.android.library` convention plugins), Robolectric 4.16.1 + JUnit4 + `junit-vintage-engine` (added to `feature:tracking:data` only, mirroring `core:database`'s existing `MigrationTest` setup), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-05-sensor-adapter-test-coverage-design.md`

## Global Constraints

- No change to `RideSensorDataSource`'s public interface or to DI wiring (`TrackingDataModule.kt`) — `AndroidRideSensorDataSource`'s constructor, `start()`, `stop()`, `observeSamples()` signatures stay identical.
- No behavior change beyond bugs the new tests legitimately catch — this is a test-coverage phase, not a rework of the fusion algorithm (already vetted in `docs/superpowers/specs/2026-07-08-gps-sensor-precision-design.md`).
- `RideSampleAssembler` must be framework-free (no `android.*` imports) so its tests run via plain `:core:domain:test`, no Robolectric.
- Follow existing test-file conventions exactly: JUnit5 (`org.junit.jupiter.api.Test`) + assertk for `core:domain`; JUnit4 (`org.junit.Test`, `@RunWith(RobolectricTestRunner::class)`) + assertk for the new Robolectric tests in `feature:tracking:data`, matching `core:database`'s `MigrationTest`.

---

### Task 1: `RideSampleAssembler` in `core:domain`

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideSampleAssembler.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSampleAssemblerTest.kt`

**Interfaces:**
- Consumes: `PositionKalmanFilter` (`core/domain/.../tracking/PositionKalmanFilter.kt`) — `fun update(latitude: Double, longitude: Double, accuracyM: Float, timestampMs: Long): FilteredPosition` and `fun reset()`. `FilteredPosition` has `latitude: Double`, `longitude: Double`, `speedMps: Double`, `bearingDegrees: Float?`, `wasGated: Boolean`. `RideSensorSample` (`core/domain/.../tracking/RideSensorSample.kt`) — unchanged, this task's output type.
- Produces: `RawGpsFix` data class and `RideSampleAssembler` class — `Task 2` (the `AndroidRideSensorDataSource` refactor) constructs `RideSampleAssembler()` and calls `.assemble(...)` / `.reset()` using exactly the signatures below.

```kotlin
data class RawGpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val fixTimeMs: Long,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    val satelliteCount: Int? = null,
)

class RideSampleAssembler(
    gpsBearingMinSpeedMps: Float = 2.0f,
    positionKalmanFilter: PositionKalmanFilter = PositionKalmanFilter(),
) {
    fun assemble(
        rawFix: RawGpsFix?,
        pressureHpa: Double?,
        altitudeFromBarometerM: Double?,
        smoothedHeadingDeg: Float?,
        nowMs: Long,
        gpsTimestampMs: Long,
        pressureTimestampMs: Long,
        headingTimestampMs: Long,
    ): RideSensorSample

    fun reset()
}
```

- [ ] **Step 1: Write the failing test file**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSampleAssemblerTest.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class RideSampleAssemblerTest {
    @Test
    fun `fresh accurate fix populates GPS fields and feeds the Kalman filter`() {
        val assembler = RideSampleAssembler()
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 5.0f,
                bearingDeg = 90f,
                altitudeM = 120.0,
                satelliteCount = 8,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = 1013.25,
                altitudeFromBarometerM = 100.0,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 1_000L,
                headingTimestampMs = 1_000L,
            )

        // First fix ever fed to a fresh PositionKalmanFilter passes through unchanged
        // (see PositionKalmanFilterTest's own "first fix passes through unchanged" case).
        assertThat(sample.latitude).isEqualTo(50.0)
        assertThat(sample.longitude).isEqualTo(19.0)
        assertThat(sample.altitudeFromGpsM).isEqualTo(120.0)
        assertThat(sample.speedFromGpsMps).isEqualTo(5.0)
        assertThat(sample.accuracyM).isEqualTo(5.0f)
        assertThat(sample.satelliteCount).isEqualTo(8)
        assertThat(sample.bearingDegrees).isEqualTo(90f)
        assertThat(sample.altitudeFromBarometerM).isEqualTo(100.0)
        assertThat(sample.pressureHpa).isEqualTo(1013.25)
    }

    @Test
    fun `unusable fix nulls GPS fields while barometer and heading still flow, even on the very first call`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = 1013.25,
                altitudeFromBarometerM = 100.0,
                smoothedHeadingDeg = 45f,
                nowMs = 1_000L,
                gpsTimestampMs = 0L,
                pressureTimestampMs = 1_000L,
                headingTimestampMs = 0L,
            )

        assertThat(sample.latitude).isNull()
        assertThat(sample.longitude).isNull()
        assertThat(sample.altitudeFromGpsM).isNull()
        assertThat(sample.speedFromGpsMps).isNull()
        assertThat(sample.accuracyM).isNull()
        assertThat(sample.satelliteCount).isNull()
        assertThat(sample.bearingDegrees).isEqualTo(45f)
        assertThat(sample.altitudeFromBarometerM).isEqualTo(100.0)
        assertThat(sample.pressureHpa).isEqualTo(1013.25)
    }

    @Test
    fun `same fix time is not re-fed to the Kalman filter`() {
        val assembler = RideSampleAssembler()
        val firstFix = RawGpsFix(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, fixTimeMs = 1_000L)
        val first =
            assembler.assemble(
                rawFix = firstFix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )
        assertThat(first.latitude).isEqualTo(50.0)
        assertThat(first.longitude).isEqualTo(19.0)

        // Same fixTimeMs, deliberately different lat/lon: proves the dedup guard
        // keys purely off fixTimeMs. If the filter were re-fed, it would blend
        // toward these new values instead of returning the cached result.
        val repeatedTimeFix = firstFix.copy(latitude = 99.0, longitude = 88.0)
        val second =
            assembler.assemble(
                rawFix = repeatedTimeFix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )

        assertThat(second.latitude).isEqualTo(50.0)
        assertThat(second.longitude).isEqualTo(19.0)
    }

    @Test
    fun `a new fix time feeds the Kalman filter again`() {
        val assembler = RideSampleAssembler()
        val firstFix = RawGpsFix(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, fixTimeMs = 1_000L)
        assembler.assemble(
            rawFix = firstFix,
            pressureHpa = null,
            altitudeFromBarometerM = null,
            smoothedHeadingDeg = null,
            nowMs = 1_000L,
            gpsTimestampMs = 1_000L,
            pressureTimestampMs = 0L,
            headingTimestampMs = 0L,
        )

        val secondFix = firstFix.copy(latitude = 50.001, fixTimeMs = 2_000L)
        val second =
            assembler.assemble(
                rawFix = secondFix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 2_000L,
                gpsTimestampMs = 2_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )

        // A genuinely new fix is blended by the filter, not passed through: the
        // result moves toward 50.001 but isn't exactly equal to either the old
        // (50.0) or new (50.001) raw value.
        assertThat(second.latitude).isGreaterThan(50.0)
        assertThat(second.latitude).isLessThan(50.001)
    }

    @Test
    fun `fast GPS bearing is used above the minimum speed`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 3.0f,
                bearingDeg = 200f,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = 10f,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(200f)
    }

    @Test
    fun `slow speed falls back to smoothed heading`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 1.0f,
                bearingDeg = 200f,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = 10f,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(10f)
    }

    @Test
    fun `unusable GPS falls back to smoothed heading`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = 77f,
                nowMs = 1_000L,
                gpsTimestampMs = 0L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(77f)
    }

    @Test
    fun `bearing is null when both GPS bearing and heading are absent`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 0L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )

        assertThat(sample.bearingDegrees).isNull()
    }

    @Test
    fun `non-finite smoothed heading is dropped to null`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = Float.NaN,
                nowMs = 1_000L,
                gpsTimestampMs = 0L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isNull()
    }

    @Test
    fun `non-finite GPS bearing is dropped to null`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 5.0f,
                bearingDeg = Float.POSITIVE_INFINITY,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )

        assertThat(sample.bearingDegrees).isNull()
    }

    @Test
    fun `heading bearing outside 0 360 is normalized`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = -10f,
                nowMs = 1_000L,
                gpsTimestampMs = 0L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(350f)
    }

    @Test
    fun `GPS bearing outside 0 360 is normalized`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 5.0f,
                bearingDeg = 370f,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(10f)
    }

    @Test
    fun `sample timestamp is the max of the per-sensor timestamps and now`() {
        val assembler = RideSampleAssembler()

        val pressureNewest =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 500L,
                gpsTimestampMs = 100L,
                pressureTimestampMs = 900L,
                headingTimestampMs = 200L,
            )
        assertThat(pressureNewest.timestampMs).isEqualTo(900L)

        val nowNewest =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 100L,
                pressureTimestampMs = 200L,
                headingTimestampMs = 300L,
            )
        assertThat(nowNewest.timestampMs).isEqualTo(1_000L)
    }

    @Test
    fun `barometer altitude is forwarded unchanged, including null`() {
        val assembler = RideSampleAssembler()

        val withAltitude =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = 123.4,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 0L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )
        assertThat(withAltitude.altitudeFromBarometerM).isEqualTo(123.4)

        val withoutAltitude =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 0L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )
        assertThat(withoutAltitude.altitudeFromBarometerM).isNull()
    }

    @Test
    fun `cached Kalman result is suppressed on a null fix but reused when the same fix reappears`() {
        val assembler = RideSampleAssembler()
        val fix = RawGpsFix(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, fixTimeMs = 1_000L)

        val first =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )
        assertThat(first.latitude).isEqualTo(50.0)

        val whileUnusable =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 2_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )
        assertThat(whileUnusable.latitude).isNull()
        assertThat(whileUnusable.longitude).isNull()

        val sameFixReappears =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 3_000L,
                gpsTimestampMs = 1_000L,
                pressureTimestampMs = 0L,
                headingTimestampMs = 0L,
            )
        assertThat(sameFixReappears.latitude).isEqualTo(50.0)
        assertThat(sameFixReappears.longitude).isEqualTo(19.0)
    }
}
```

- [ ] **Step 2: Run the test file to verify it fails to compile**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideSampleAssemblerTest"`
Expected: FAIL — `RideSampleAssembler` and `RawGpsFix` are unresolved references (the production file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideSampleAssembler.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

/**
 * A single raw GPS fix, in the exact field types [android.location.Location]'s
 * own getters return, so a caller building one from a real fix does no
 * conversion beyond null-checking `has*()`. Absent (`null`) entirely when the
 * caller has determined the underlying fix is stale or too inaccurate to use.
 */
data class RawGpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val fixTimeMs: Long,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    val satelliteCount: Int? = null,
)

/**
 * Fuses one cycle's raw GPS/barometer/heading readings into a
 * [RideSensorSample]. Wraps a [PositionKalmanFilter], feeding it at most once
 * per distinct [RawGpsFix.fixTimeMs] — [assemble] is called far more often
 * than GPS produces new fixes (barometer ~2Hz, heading on every ~2° step),
 * and a fix stays usable for a few seconds after it arrives; without this
 * dedup guard the filter would run a full predict+update cycle multiple
 * times per second against an unchanged position, dragging its velocity
 * estimate toward zero and over-shrinking its covariance between real fixes.
 *
 * Bearing prefers GPS course-over-ground when moving fast enough to trust it
 * over the smoothed magnetometer/rotation-vector heading (which is easily
 * disturbed by a bike frame and the phone's own fields at low speed), falling
 * back to the heading otherwise. Whichever source is chosen is sanitized:
 * non-finite values are dropped, finite ones normalized into `[0, 360)`.
 */
class RideSampleAssembler(
    private val gpsBearingMinSpeedMps: Float = 2.0f,
    private val positionKalmanFilter: PositionKalmanFilter = PositionKalmanFilter(),
) {
    private var lastKalmanFedFixTimeMs: Long? = null
    private var lastKalmanResult: FilteredPosition? = null

    fun assemble(
        rawFix: RawGpsFix?,
        pressureHpa: Double?,
        altitudeFromBarometerM: Double?,
        smoothedHeadingDeg: Float?,
        nowMs: Long,
        gpsTimestampMs: Long,
        pressureTimestampMs: Long,
        headingTimestampMs: Long,
    ): RideSensorSample {
        val filteredPosition = feedKalmanFilter(rawFix)

        val gpsBearing =
            rawFix
                ?.takeIf { it.speedMps != null && it.speedMps >= gpsBearingMinSpeedMps }
                ?.bearingDeg
        val bearing =
            (gpsBearing ?: smoothedHeadingDeg)
                ?.takeIf { it.isFinite() }
                ?.let { ((it % 360f) + 360f) % 360f }

        return RideSensorSample(
            timestampMs = maxOf(gpsTimestampMs, pressureTimestampMs, headingTimestampMs, nowMs),
            latitude = filteredPosition?.latitude,
            longitude = filteredPosition?.longitude,
            altitudeFromGpsM = rawFix?.altitudeM,
            altitudeFromBarometerM = altitudeFromBarometerM,
            speedFromGpsMps = rawFix?.speedMps?.toDouble(),
            accuracyM = rawFix?.accuracyM,
            bearingDegrees = bearing,
            satelliteCount = rawFix?.satelliteCount,
            pressureHpa = pressureHpa,
        )
    }

    /** Clears the wrapped Kalman filter and the fix-dedup state, e.g. when tracking stops. */
    fun reset() {
        positionKalmanFilter.reset()
        lastKalmanFedFixTimeMs = null
        lastKalmanResult = null
    }

    private fun feedKalmanFilter(rawFix: RawGpsFix?): FilteredPosition? {
        if (rawFix == null) return null
        return if (rawFix.fixTimeMs != lastKalmanFedFixTimeMs) {
            lastKalmanFedFixTimeMs = rawFix.fixTimeMs
            positionKalmanFilter
                .update(
                    latitude = rawFix.latitude,
                    longitude = rawFix.longitude,
                    accuracyM = rawFix.accuracyM,
                    timestampMs = rawFix.fixTimeMs,
                ).also { lastKalmanResult = it }
        } else {
            lastKalmanResult
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideSampleAssemblerTest"`
Expected: PASS (all 15 test methods green).

- [ ] **Step 5: Run the full `core:domain` suite to confirm no regression**

Run: `./gradlew :core:domain:test`
Expected: PASS — in particular `PositionKalmanFilterTest` and `HeadingSmootherTest` are untouched and still pass, since `RideSampleAssembler` only wraps/calls them, never modifies them.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideSampleAssembler.kt
git add core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSampleAssemblerTest.kt
git commit -m "$(cat <<'EOF'
test: add RideSampleAssembler with exhaustive unit coverage

Extracts AndroidRideSensorDataSource.emitSample()'s GPS/barometer/heading
fusion and gating logic into a framework-free, directly testable class,
per docs/superpowers/specs/2026-09-05-sensor-adapter-test-coverage-design.md.
Not yet wired up -- AndroidRideSensorDataSource still has its own inline
copy of this logic until the next task.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm
EOF
)"
```

---

### Task 2: Refactor `AndroidRideSensorDataSource` to delegate to `RideSampleAssembler`

**Files:**
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt`

**Interfaces:**
- Consumes: `RideSampleAssembler` and `RawGpsFix` from Task 1 (`core:domain:tracking`), exact signatures as declared there.
- Produces: no new public API — `AndroidRideSensorDataSource`'s existing public surface (`start()`, `stop()`, `observeSamples()`) is unchanged. Task 3's tests construct and call this class exactly as before.

This task has no new automated test of its own — `AndroidRideSensorDataSource` isn't unit-testable without Robolectric (it constructs `LocationManager`/`SensorManager` from a real `Context` in its constructor). Task 3 adds the Robolectric coverage that verifies this refactor didn't break the real class. Each step below is a precise, mechanical find-and-replace; there is no behavior change.

- [ ] **Step 1: Update the import block**

In `AndroidRideSensorDataSource.kt`, replace:

```kotlin
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.HeadingSmoother
import com.speedevand.inkride.core.domain.tracking.PositionKalmanFilter
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.SensorError
```

with:

```kotlin
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.HeadingSmoother
import com.speedevand.inkride.core.domain.tracking.RawGpsFix
import com.speedevand.inkride.core.domain.tracking.RideSampleAssembler
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.SensorError
```

- [ ] **Step 2: Replace the Kalman-filter fields with a `RideSampleAssembler` field**

Replace:

```kotlin
    private val headingSmoother = HeadingSmoother()

    private val positionKalmanFilter = PositionKalmanFilter()

    // Timestamp (the GPS fix's own device clock, i.e. Location.time) of the
    // last fix actually fed into positionKalmanFilter, and the filter's last
    // output. emitSample() fires far more often than GPS produces new fixes
    // (barometer ~2Hz, heading on every ~2° step), and useGpsData stays true
    // for up to maxGpsFixAgeMs after a fix — without this guard the filter
    // would run a full predict+update cycle multiple times per second against
    // an unchanged position, dragging its velocity estimate toward zero and
    // over-shrinking its covariance between real fixes.
    private var lastKalmanFedLocationTimeMs: Long = 0L
    private var lastKalmanResult: com.speedevand.inkride.core.domain.tracking.FilteredPosition? = null
```

with:

```kotlin
    private val headingSmoother = HeadingSmoother()

    // Wraps PositionKalmanFilter with a same-fix dedup guard: emitSample()
    // fires far more often than GPS produces new fixes (barometer ~2Hz,
    // heading on every ~2° step), and a fix stays usable for up to
    // maxGpsFixAgeMs — without the guard the filter would run a full
    // predict+update cycle multiple times per second against an unchanged
    // position, dragging its velocity estimate toward zero and
    // over-shrinking its covariance between real fixes.
    private val sampleAssembler = RideSampleAssembler()
```

- [ ] **Step 3: Remove the now-unused `gpsBearingMinSpeedMps` field**

Bearing-source selection is now entirely `RideSampleAssembler`'s responsibility (it has its own `2.0f` default), so this class has no remaining use for the constant. Replace:

```kotlin
    // Above this speed, GPS course-over-ground is more trustworthy than the
    // rotation-vector heading (which is easily disturbed by the bike frame
    // and the phone's own fields in particular).
    private val gpsBearingMinSpeedMps: Float = 2.0f

    // Satellite count from GnssStatus — used for GPS quality assessment.
```

with:

```kotlin
    // Satellite count from GnssStatus — used for GPS quality assessment.
```

- [ ] **Step 4: Shrink `emitSample()` to delegate to the assembler**

Replace the entire `emitSample()` function body:

```kotlin
    private fun emitSample() {
        val location = lastLocation
        val pressureHpa = lastPressureHpa
        val altitudeFromBarometer =
            pressureHpa?.let {
                SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it).toDouble()
            }

        // Validate GPS data freshness and quality at the source.
        // GPS fields are nulled out when the fix is stale or too inaccurate,
        // but non-GPS sensor data (barometer, heading) still flows through.
        val now = System.currentTimeMillis()
        val isGpsFresh = location != null && (now - location.time) < maxGpsFixAgeMs
        val isGpsAccurate = location != null && location.hasAccuracy() && location.accuracy <= maxSourceAccuracyM
        val useGpsData = isGpsFresh && isGpsAccurate

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
                val fixTimeMs = location!!.time
                if (fixTimeMs != lastKalmanFedLocationTimeMs) {
                    lastKalmanFedLocationTimeMs = fixTimeMs
                    positionKalmanFilter
                        .update(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyM = location.accuracy,
                            timestampMs = fixTimeMs,
                        ).also { lastKalmanResult = it }
                } else {
                    lastKalmanResult
                }
            } else {
                null
            }

        // Bearing source: while moving, GPS course-over-ground is far more
        // reliable than the magnetometer (which is distorted by the bike frame
        // and the phone's own fields). When slow or stopped, fall back to the
        // smoothed magnetometer heading so the compass still points somewhere.
        val gpsBearing =
            if (useGpsData) {
                location.let {
                    if (it.hasBearing() && it.hasSpeed() && it.speed >= gpsBearingMinSpeedMps) it.bearing else null
                }
            } else {
                null
            }
        // Drop NaN/Infinity (rotation-matrix or driver glitches) and normalize to
        // [0, 360) so downstream consumers never see an out-of-range heading.
        val bearing =
            (gpsBearing ?: lastHeading)
                ?.takeIf { it.isFinite() }
                ?.let { ((it % 360f) + 360f) % 360f }

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
    }
```

with:

```kotlin
    private fun emitSample() {
        val location = lastLocation
        val pressureHpa = lastPressureHpa
        val altitudeFromBarometer =
            pressureHpa?.let {
                SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it).toDouble()
            }

        // Validate GPS data freshness and quality at the source. A stale or
        // inaccurate fix is treated as no fix at all (rawFix = null); non-GPS
        // sensor data (barometer, heading) still flows through regardless.
        val now = System.currentTimeMillis()
        val isGpsFresh = location != null && (now - location.time) < maxGpsFixAgeMs
        val isGpsAccurate = location != null && location.hasAccuracy() && location.accuracy <= maxSourceAccuracyM
        val useGpsData = isGpsFresh && isGpsAccurate

        val rawFix =
            if (useGpsData) {
                val fix = location!!
                RawGpsFix(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyM = fix.accuracy,
                    fixTimeMs = fix.time,
                    speedMps = if (fix.hasSpeed()) fix.speed else null,
                    bearingDeg = if (fix.hasBearing()) fix.bearing else null,
                    altitudeM = if (fix.hasAltitude()) fix.altitude else null,
                    satelliteCount = lastSatelliteCount,
                )
            } else {
                null
            }

        val sample =
            sampleAssembler.assemble(
                rawFix = rawFix,
                pressureHpa = pressureHpa?.toDouble(),
                altitudeFromBarometerM = altitudeFromBarometer,
                smoothedHeadingDeg = lastHeading,
                nowMs = now,
                gpsTimestampMs = lastGpsTimestampMs,
                pressureTimestampMs = lastPressureTimestampMs,
                headingTimestampMs = lastHeadingTimestampMs,
            )

        samplesFlow.tryEmit(sample)
    }
```

- [ ] **Step 5: Update `stop()` to reset the assembler**

Replace:

```kotlin
        headingSmoother.reset()
        positionKalmanFilter.reset()
        lastKalmanFedLocationTimeMs = 0L
        lastKalmanResult = null
        magneticDeclinationDeg = 0f
```

with:

```kotlin
        headingSmoother.reset()
        sampleAssembler.reset()
        magneticDeclinationDeg = 0f
```

- [ ] **Step 6: Compile the module**

Run: `./gradlew :feature:tracking:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails, check for a leftover reference to `positionKalmanFilter`, `lastKalmanFedLocationTimeMs`, `lastKalmanResult`, or `gpsBearingMinSpeedMps` — Step 1-5 must have removed every use.

- [ ] **Step 7: Commit**

```bash
git add feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt
git commit -m "$(cat <<'EOF'
refactor: delegate AndroidRideSensorDataSource fusion logic to RideSampleAssembler

emitSample() now builds a RawGpsFix from the raw Location (if fresh and
accurate) and delegates all fusion/gating/bearing-selection decisions to
RideSampleAssembler (added in the previous commit), instead of inlining
that logic. No behavior change -- verified by the Robolectric integration
test in the next task, since the existing instrumented E2E suite injects
a fake RideSensorDataSource and never exercised this class.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm
EOF
)"
```

---

### Task 3: Robolectric tests for `start()` gating and the real GPS-fix wiring

**Files:**
- Modify: `feature/tracking/data/build.gradle.kts`
- Test: `feature/tracking/data/src/test/kotlin/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSourceStartTest.kt`

**Interfaces:**
- Consumes: `AndroidRideSensorDataSource(context: Context)` (Task 2's refactored class — public constructor/`start()`/`stop()`/`observeSamples()` unchanged), `SensorError.Permission.LOCATION_DENIED`, `SensorError.Hardware.GPS_MISSING` (`core/domain/.../tracking/SensorError.kt`), `Result.Success`/`Result.Error` (`core/domain/.../Result.kt`).
- Produces: nothing consumed by a later task — this is the terminal verification for Tasks 1-2.

- [ ] **Step 1: Add Robolectric test dependencies**

Modify `feature/tracking/data/build.gradle.kts` — replace:

```kotlin
dependencies {
    implementation(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
}
```

with:

```kotlin
dependencies {
    implementation(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
}
```

(JUnit5/assertk/coroutines-test are already provided by the `inkride.android.library` convention plugin — only the Robolectric/JUnit4-bridge pieces are missing, identical to `core:database`'s existing setup.)

- [ ] **Step 2: Write the failing test file**

Create `feature/tracking/data/src/test/kotlin/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSourceStartTest.kt`:

```kotlin
package com.speedevand.inkride.tracking.data

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.SensorError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidRideSensorDataSourceStartTest {
    // Declared as ContextWrapper (not Context or Application) so shadowOf(context)
    // resolves to the Shadows facade's shadowOf(ContextWrapper): ShadowContextWrapper
    // overload, which is the one carrying grantPermissions/denyPermissions.
    // shadowOf(Context) has no overload at all, and shadowOf(Application) resolves
    // to ShadowApplication, which lacks both methods -- confirmed against the
    // actual Robolectric 4.16.1 shadows-framework jar's generated Shadows class.
    private val context = ApplicationProvider.getApplicationContext<ContextWrapper>()
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Test
    fun `start returns LOCATION_DENIED when neither permission is granted`() {
        shadowOf(context).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val dataSource = AndroidRideSensorDataSource(context)

        val result = dataSource.start()

        assertThat(result).isInstanceOf<Result.Error<SensorError.Permission>>()
        assertThat((result as Result.Error).error).isEqualTo(SensorError.Permission.LOCATION_DENIED)
    }

    @Test
    fun `start returns GPS_MISSING when the GPS provider is absent`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(locationManager).removeProvider(LocationManager.GPS_PROVIDER)
        val dataSource = AndroidRideSensorDataSource(context)

        val result = dataSource.start()

        assertThat(result).isInstanceOf<Result.Error<SensorError.Hardware>>()
        assertThat((result as Result.Error).error).isEqualTo(SensorError.Hardware.GPS_MISSING)
    }

    @Test
    fun `start succeeds and is idempotent when permission is granted and GPS exists`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val dataSource = AndroidRideSensorDataSource(context)

        val first = dataSource.start()
        val second = dataSource.start()

        assertThat(first).isInstanceOf<Result.Success<Unit>>()
        assertThat(second).isInstanceOf<Result.Success<Unit>>()
    }

    @Test
    fun `a simulated GPS fix flows through the real listener into an emitted sample`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val dataSource = AndroidRideSensorDataSource(context)
            dataSource.start()

            val collected = mutableListOf<RideSensorSample>()
            val collectorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            collectorScope.launch { dataSource.observeSamples().collect { collected.add(it) } }

            val firstFix =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 50.0
                    longitude = 19.0
                    accuracy = 5.0f
                    speed = 4.0f
                    bearing = 90f
                    time = System.currentTimeMillis()
                }
            shadowOf(locationManager).simulateLocation(firstFix)
            // AndroidRideSensorDataSource posts listener callbacks through a
            // Handler(Looper.getMainLooper()); Robolectric's default paused
            // looper mode queues them until idled.
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(collected).hasSize(1)
            assertThat(collected.last().latitude).isEqualTo(50.0)
            assertThat(collected.last().longitude).isEqualTo(19.0)
            assertThat(collected.last().speedFromGpsMps).isEqualTo(4.0)
            assertThat(collected.last().accuracyM).isEqualTo(5.0f)

            val secondFix =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 50.01
                    longitude = 19.01
                    accuracy = 5.0f
                    speed = 4.0f
                    bearing = 90f
                    time = firstFix.time + 1_000L
                }
            shadowOf(locationManager).simulateLocation(secondFix)
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(collected).hasSize(2)

            collectorScope.cancel()
        }
}
```

- [ ] **Step 3: Run the tests to verify current behavior**

Run: `./gradlew :feature:tracking:data:testDebugUnitTest --tests "com.speedevand.inkride.tracking.data.AndroidRideSensorDataSourceStartTest"`

Expected: all four tests PASS. Unlike Task 1's greenfield class, `start()`'s gating logic and the refactored `emitSample()` wiring already exist and are believed correct (per the 2026-07-08 precision audit and Task 2's mechanical refactor) — these tests characterize and lock in that behavior rather than drive new implementation. If any test fails, that is a real bug Task 2's refactor introduced (e.g. a typo in the `RawGpsFix` field mapping) or a wrong assumption about a Robolectric default (e.g. the GPS provider not being pre-registered) — fix the production code or the test setup accordingly before proceeding; do not weaken an assertion to force a pass.

- [ ] **Step 4: Run the full module suite**

Run: `./gradlew :feature:tracking:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/tracking/data/build.gradle.kts
git add feature/tracking/data/src/test/kotlin/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSourceStartTest.kt
git commit -m "$(cat <<'EOF'
test: add Robolectric coverage for AndroidRideSensorDataSource.start()

Covers the LOCATION_DENIED/GPS_MISSING/Success gating branches, plus one
end-to-end integration test driving a real Location fix through the
actual registered LocationListener via ShadowLocationManager.simulateLocation.
This is the only test exercising the real class -- the instrumented
ride-tracking E2E suite injects FakeRideSensorDataSource and never did.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AovXXvB5mBynKFxswkqfGm
EOF
)"
```

---

### Task 4: Full verification sweep

**Files:** none (verification only).

**Interfaces:** none.

- [ ] **Step 1: Run every affected module's test suite**

Run: `./gradlew :core:domain:test :feature:tracking:data:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Run lint on the modified modules**

Run: `./gradlew :core:domain:test :feature:tracking:data:lintDebug`
Expected: BUILD SUCCESSFUL (no new lint findings introduced by the refactor).

- [ ] **Step 3: Sanity-check the instrumented ride-tracking E2E suite still boots (requires a connected device/emulator)**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.tracking.*"`
Expected: PASS. Per the spec's "Correction" section, this does **not** exercise `AndroidRideSensorDataSource` (it injects `FakeRideSensorDataSource`), so a pass here only confirms DI wiring and app startup are undisturbed — it is not a regression check on this phase's actual changes. Skip this step if no device/emulator is available; Task 3's Robolectric integration test is the real regression backstop for this phase.

- [ ] **Step 4: Report**

No commit — this task only verifies Tasks 1-3's commits. If any check fails, return to the relevant task, fix, and re-commit there rather than adding a fixup commit here.
