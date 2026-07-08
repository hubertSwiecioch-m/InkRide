# Elevation Chart in Ride Detail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a static, monochrome altitude-vs-distance chart in `RideDetailScreen`, built from the already-recorded `RideTrackPoint` data.

**Architecture:** A pure calculator (`buildElevationProfile`) in `:core:domain` turns a ride's track points into a distance-indexed, downsampled altitude series with pre-computed min/max. `RideDetailViewModel` calls it once alongside its existing track-point load and maps the result to a UI type (`ElevationChartUi`) carrying unit-converted labels. A new Compose `Canvas`-based composable (`ElevationChart`) draws it as a single static polyline with two text labels — no animation, no interactivity, matching the E-Ink constraint. Along the way, two duplicated private `haversine` implementations already in the codebase are consolidated into one shared function the new calculator also uses.

**Tech Stack:** Kotlin, Jetpack Compose (`Canvas`/`drawPath`), JUnit5 + assertk, Koin (no new bindings needed — everything here is either a pure function or already-injected).

## Global Constraints

- No new permissions, no DB/schema changes, no new Gradle dependencies (Compose `Canvas` is already available).
- E-Ink constraint: no animation, no gesture handling, one static draw per composition; monochrome only (`MaterialTheme.colorScheme.onSurface`).
- Altitude labels must respect the user's metric/imperial setting using the same `altitudeFactor`/unit-suffix convention already used for `elevationGainM` (`RideDetailContract.kt`).
- `ktlintCheck` and `lintDebug` run in CI — no unused imports left behind after removing code.
- Min/max altitude (and their chart position) must always be computed from the full point series, never from the downsampled render series.

---

### Task 1: Shared `haversineMeters` utility

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/GeoDistance.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/GeoDistanceTest.kt`

**Interfaces:**
- Produces: `fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double` — top-level, package `com.speedevand.inkride.core.domain.tracking`, used by Task 2, 3, and 4.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class GeoDistanceTest {
    @Test
    fun `same point has zero distance`() {
        assertThat(haversineMeters(52.0, 21.0, 52.0, 21.0)).isEqualTo(0.0)
    }

    @Test
    fun `one hundredth of a degree of latitude is about 1112 meters`() {
        val distance = haversineMeters(52.0, 21.0, 52.01, 21.0)

        assertThat(distance).isCloseTo(1111.95, 1.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.GeoDistanceTest"`
Expected: FAIL — `Unresolved reference: haversineMeters`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.speedevand.inkride.core.domain.tracking

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance between two lat/lng points, in meters (haversine formula). */
fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val a =
        sin(dLat / 2).pow(2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
    return earthRadiusM * 2 * asin(min(1.0, sqrt(a)))
}

private fun Double.toRadians(): Double = this * PI / 180.0
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.GeoDistanceTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/GeoDistance.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/GeoDistanceTest.kt
git commit -m "Add shared haversineMeters distance utility"
```

---

### Task 2: Migrate `RouteFollower` to the shared utility

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RouteFollower.kt`

**Interfaces:**
- Consumes: `haversineMeters` from Task 1 (same package, no import needed).

- [ ] **Step 1: Remove the private `haversine` function**

In `RouteFollower.kt`, delete this block (currently lines 190–202, right before `private fun Double.toRadians()`):

```kotlin
    private fun haversine(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a =
            sin(dLat / 2).pow(2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_M * 2 * asin(min(1.0, sqrt(a)))
    }

```

`private fun Double.toRadians()` stays — it's also used at `cos(latitude.toRadians())` in `projectToSegment`.

- [ ] **Step 2: Update the three call sites**

Replace (line ~55):
```kotlin
            val d = haversine(latitude, longitude, pts[0].latitude, pts[0].longitude)
```
with:
```kotlin
            val d = haversineMeters(latitude, longitude, pts[0].latitude, pts[0].longitude)
```

Replace (line ~62):
```kotlin
                        ?.let { haversine(latitude, longitude, it.latitude, it.longitude) },
```
with:
```kotlin
                        ?.let { haversineMeters(latitude, longitude, it.latitude, it.longitude) },
```

Replace (lines ~123–129):
```kotlin
            cum[i] = cum[i - 1] +
                haversine(
                    pts[i - 1].latitude,
                    pts[i - 1].longitude,
                    pts[i].latitude,
                    pts[i].longitude,
                )
```
with:
```kotlin
            cum[i] = cum[i - 1] +
                haversineMeters(
                    pts[i - 1].latitude,
                    pts[i - 1].longitude,
                    pts[i].latitude,
                    pts[i].longitude,
                )
```

- [ ] **Step 3: Remove now-unused imports**

`asin`, `min`, `pow`, and `sin` were only used inside the deleted `haversine` function. Remove these three lines from the top of `RouteFollower.kt`:
```kotlin
import kotlin.math.asin
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
```
Keep `PI`, `cos`, and `sqrt` — all three are still used elsewhere in the file (`toRadians()`, `projectToSegment`).

- [ ] **Step 4: Run the existing test suite to confirm no regression**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RouteFollowerTest"`
Expected: PASS (all existing tests, unchanged assertions)

- [ ] **Step 5: Run ktlint to confirm no unused-import violations**

Run: `./gradlew ktlintCheck`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RouteFollower.kt
git commit -m "Migrate RouteFollower to the shared haversineMeters utility"
```

---

### Task 3: Migrate `RideMetricsCalculator` to the shared utility

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`

**Interfaces:**
- Consumes: `haversineMeters` from Task 1 (same package, no import needed).

- [ ] **Step 1: Remove the private `haversineDistanceMeters` function and its `toRadians` extension**

Delete this block (currently lines 570–590, the last two members of the class):

```kotlin
    private fun haversineDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()

        val a =
            sin(dLat / 2).pow(2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) *
                sin(dLon / 2).pow(2)

        val c = 2 * asin(min(1.0, sqrt(a)))
        return earthRadiusM * c
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
```

Unlike `RouteFollower`, this file's `toRadians()` extension is used *only* inside the function being deleted, so both go together, leaving the closing `}` of the class as the last line.

- [ ] **Step 2: Update the three call sites**

Replace (line ~202):
```kotlin
                        haversineDistanceMeters(
                            lastLocationSample!!.latitude!!,
                            lastLocationSample!!.longitude!!,
                            sample.latitude,
                            sample.longitude,
                        )
```
with:
```kotlin
                        haversineMeters(
                            lastLocationSample!!.latitude!!,
                            lastLocationSample!!.longitude!!,
                            sample.latitude,
                            sample.longitude,
                        )
```

Replace (line ~236):
```kotlin
                    val jumpDist = haversineDistanceMeters(oldest.first, oldest.second, middle.first, middle.second)
```
with:
```kotlin
                    val jumpDist = haversineMeters(oldest.first, oldest.second, middle.first, middle.second)
```

Replace (line ~237):
```kotlin
                    val returnDist = haversineDistanceMeters(oldest.first, oldest.second, sample.latitude, sample.longitude)
```
with:
```kotlin
                    val returnDist = haversineMeters(oldest.first, oldest.second, sample.latitude, sample.longitude)
```

- [ ] **Step 3: Remove now-unused imports**

`PI`, `asin`, `cos`, `min`, `pow`, and `sin` were used *only* inside the deleted function/extension (verified: no other call sites in the file). Remove these six lines from the top of `RideMetricsCalculator.kt`:
```kotlin
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
```
Keep `abs`, `max`, and `sqrt` (via the shared utility's own import, not this file) — `abs`/`max` are each used elsewhere in the file (`abs(locationSpeedMps - lastSpeedMps)`, `max(maxSpeedMps, speedMps)`, etc.); `sqrt` was already only used inside the deleted block too, so remove it as well:
```kotlin
import kotlin.math.sqrt
```

- [ ] **Step 4: Run the existing test suite to confirm no regression**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest"`
Expected: PASS (all existing tests, unchanged assertions)

- [ ] **Step 5: Run ktlint to confirm no unused-import violations**

Run: `./gradlew ktlintCheck`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt
git commit -m "Migrate RideMetricsCalculator to the shared haversineMeters utility"
```

---

### Task 4: `ElevationProfileBuilder` pure calculator

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/ElevationProfileBuilder.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/ElevationProfileBuilderTest.kt`

**Interfaces:**
- Consumes: `haversineMeters` (Task 1); `com.speedevand.inkride.core.domain.history.RideTrackPoint(timestampMs, latitude, longitude, altitudeM, accuracyM)`.
- Produces:
  - `data class ElevationProfilePoint(val distanceKm: Double, val altitudeM: Double)`
  - `data class ElevationProfile(val points: List<ElevationProfilePoint>, val minAltitudeM: Double, val maxAltitudeM: Double, val minAltitudeDistanceKm: Double, val maxAltitudeDistanceKm: Double)`
  - `fun buildElevationProfile(points: List<RideTrackPoint>, maxSamples: Int = 200): ElevationProfile?`
  — used by Task 6 (`RideDetailViewModel`) and Task 5 (`ElevationProfile.toChartUi`).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import org.junit.jupiter.api.Test

class ElevationProfileBuilderTest {
    @Test
    fun `empty list returns null`() {
        assertThat(buildElevationProfile(emptyList())).isNull()
    }

    @Test
    fun `single point returns null`() {
        val points = listOf(RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0))

        assertThat(buildElevationProfile(points)).isNull()
    }

    @Test
    fun `all points missing altitude returns null`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = null),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = null),
            )

        assertThat(buildElevationProfile(points)).isNull()
    }

    @Test
    fun `points missing altitude are excluded from the series`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.005, longitude = 21.0, altitudeM = null),
                RideTrackPoint(timestampMs = 2000L, latitude = 52.01, longitude = 21.0, altitudeM = 110.0),
            )

        val profile = buildElevationProfile(points)

        assertThat(profile).isNotNull()
        assertThat(profile!!.points).hasSize(2)
        assertThat(profile.points[0].distanceKm).isCloseTo(0.0, 0.001)
        assertThat(profile.points[1].distanceKm).isCloseTo(1.112, 0.01)
    }

    @Test
    fun `min and max altitude are found with their distance position`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = 150.0),
                RideTrackPoint(timestampMs = 2000L, latitude = 52.02, longitude = 21.0, altitudeM = 80.0),
            )

        val profile = buildElevationProfile(points)!!

        assertThat(profile.maxAltitudeM).isEqualTo(150.0)
        assertThat(profile.maxAltitudeDistanceKm).isCloseTo(1.112, 0.01)
        assertThat(profile.minAltitudeM).isEqualTo(80.0)
        assertThat(profile.minAltitudeDistanceKm).isCloseTo(2.224, 0.02)
    }

    @Test
    fun `a large series is downsampled to at most maxSamples points`() {
        val points =
            (0 until 500).map { i ->
                RideTrackPoint(
                    timestampMs = i * 1000L,
                    latitude = 52.0 + i * 0.0001,
                    longitude = 21.0,
                    altitudeM = 100.0 + i,
                )
            }

        val profile = buildElevationProfile(points, maxSamples = 200)!!

        assertThat(profile.points.size).isLessThan(201)
    }

    @Test
    fun `a series smaller than maxSamples is not downsampled`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = 110.0),
                RideTrackPoint(timestampMs = 2000L, latitude = 52.02, longitude = 21.0, altitudeM = 120.0),
            )

        val profile = buildElevationProfile(points, maxSamples = 200)!!

        assertThat(profile.points).hasSize(3)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.ElevationProfileBuilderTest"`
Expected: FAIL — `Unresolved reference: buildElevationProfile`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.history.RideTrackPoint
import kotlin.math.min

data class ElevationProfilePoint(
    val distanceKm: Double,
    val altitudeM: Double,
)

data class ElevationProfile(
    val points: List<ElevationProfilePoint>,
    val minAltitudeM: Double,
    val maxAltitudeM: Double,
    val minAltitudeDistanceKm: Double,
    val maxAltitudeDistanceKm: Double,
)

private const val DEFAULT_MAX_SAMPLES = 200

/**
 * Turns a ride's recorded track into a distance-indexed altitude profile for
 * charting. Points without altitude are dropped before distance is
 * accumulated, so distance is measured only between consecutive points that
 * actually have a reading. Min/max are found on the full (pre-downsample)
 * series so a real peak can never be lost or mis-positioned by decimation.
 * Returns null when fewer than 2 points have altitude data.
 */
fun buildElevationProfile(
    points: List<RideTrackPoint>,
    maxSamples: Int = DEFAULT_MAX_SAMPLES,
): ElevationProfile? {
    val withAltitude = points.filter { it.altitudeM != null }
    if (withAltitude.size < 2) return null

    val series = mutableListOf(0.0 to withAltitude[0].altitudeM!!)
    var cumulativeM = 0.0
    for (i in 1 until withAltitude.size) {
        val prev = withAltitude[i - 1]
        val curr = withAltitude[i]
        cumulativeM += haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
        series.add(cumulativeM / 1000.0 to curr.altitudeM!!)
    }

    val minEntry = series.minBy { it.second }
    val maxEntry = series.maxBy { it.second }

    val downsampled =
        if (series.size <= maxSamples) {
            series.map { ElevationProfilePoint(it.first, it.second) }
        } else {
            bucketAverage(series, maxSamples)
        }

    return ElevationProfile(
        points = downsampled,
        minAltitudeM = minEntry.second,
        maxAltitudeM = maxEntry.second,
        minAltitudeDistanceKm = minEntry.first,
        maxAltitudeDistanceKm = maxEntry.first,
    )
}

private fun bucketAverage(
    series: List<Pair<Double, Double>>,
    bucketCount: Int,
): List<ElevationProfilePoint> {
    val totalDistanceKm = series.last().first
    if (totalDistanceKm <= 0.0) {
        // All points at the same position (e.g. a stationary trainer ride) —
        // bucketing by distance would divide by zero, so collapse to one point.
        return listOf(ElevationProfilePoint(0.0, series.map { it.second }.average()))
    }
    val bucketWidthKm = totalDistanceKm / bucketCount
    val buckets = Array(bucketCount) { mutableListOf<Pair<Double, Double>>() }
    series.forEach { (distanceKm, altitudeM) ->
        val index = min(bucketCount - 1, (distanceKm / bucketWidthKm).toInt())
        buckets[index].add(distanceKm to altitudeM)
    }
    return buckets.filter { it.isNotEmpty() }.map { bucket ->
        ElevationProfilePoint(
            distanceKm = bucket.map { it.first }.average(),
            altitudeM = bucket.map { it.second }.average(),
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.ElevationProfileBuilderTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/ElevationProfileBuilder.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/ElevationProfileBuilderTest.kt
git commit -m "Add ElevationProfileBuilder to derive a chartable altitude profile"
```

---

### Task 5: `ElevationChartUi` mapping in `RideDetailContract`

**Files:**
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailContract.kt`
- Test: `feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideRecordMappingTest.kt`

**Interfaces:**
- Consumes: `ElevationProfile` (Task 4); `MeasurementUnits` (existing, `com.speedevand.inkride.core.domain.settings`); `CoreConstants.{FORMAT_NO_DECIMALS, UNIT_M, UNIT_FT}` (existing).
- Produces:
  - `data class ElevationPointUi(val distanceKm: Double, val altitudeM: Double)`
  - `data class ElevationChartUi(val points: List<ElevationPointUi>, val maxAltitudeLabel: String, val maxAltitudeDistanceFraction: Float, val minAltitudeLabel: String, val minAltitudeDistanceFraction: Float)`
  - `fun ElevationProfile.toChartUi(units: MeasurementUnits = MeasurementUnits.METRIC): ElevationChartUi`
  — used by Task 6 (`RideDetailViewModel`) and Task 7 (`ElevationChart` composable, `RideDetailState.elevationChart`).

- [ ] **Step 1: Write the failing tests**

Add to `RideRecordMappingTest.kt` (new imports: `com.speedevand.inkride.core.domain.tracking.ElevationProfile`, `com.speedevand.inkride.core.domain.tracking.ElevationProfilePoint`):

```kotlin
    @Test
    fun `toChartUi with metric units formats altitude labels`() {
        val profile =
            ElevationProfile(
                points = listOf(ElevationProfilePoint(0.0, 100.0), ElevationProfilePoint(5.0, 150.0)),
                minAltitudeM = 100.0,
                maxAltitudeM = 150.0,
                minAltitudeDistanceKm = 0.0,
                maxAltitudeDistanceKm = 5.0,
            )

        val ui = profile.toChartUi(MeasurementUnits.METRIC)

        assertThat(ui.maxAltitudeLabel).isEqualTo("150 m")
        assertThat(ui.minAltitudeLabel).isEqualTo("100 m")
        assertThat(ui.maxAltitudeDistanceFraction).isEqualTo(1.0f)
        assertThat(ui.minAltitudeDistanceFraction).isEqualTo(0.0f)
    }

    @Test
    fun `toChartUi with imperial units converts altitude labels`() {
        val profile =
            ElevationProfile(
                points = listOf(ElevationProfilePoint(0.0, 100.0), ElevationProfilePoint(5.0, 150.0)),
                minAltitudeM = 100.0,
                maxAltitudeM = 150.0,
                minAltitudeDistanceKm = 0.0,
                maxAltitudeDistanceKm = 5.0,
            )

        val ui = profile.toChartUi(MeasurementUnits.IMPERIAL)

        assertThat(ui.maxAltitudeLabel).isEqualTo("492 ft")
        assertThat(ui.minAltitudeLabel).isEqualTo("328 ft")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest --tests "com.speedevand.inkride.history.presentation.RideRecordMappingTest"`
Expected: FAIL — `Unresolved reference: toChartUi`

- [ ] **Step 3: Write the implementation**

Add to `RideDetailContract.kt` (after the existing `TrackPointUi` declaration), plus a new import `com.speedevand.inkride.core.domain.tracking.ElevationProfile`:

```kotlin
data class ElevationPointUi(
    val distanceKm: Double,
    val altitudeM: Double,
)

data class ElevationChartUi(
    val points: List<ElevationPointUi>,
    val maxAltitudeLabel: String,
    val maxAltitudeDistanceFraction: Float,
    val minAltitudeLabel: String,
    val minAltitudeDistanceFraction: Float,
)

fun ElevationProfile.toChartUi(units: MeasurementUnits = MeasurementUnits.METRIC): ElevationChartUi {
    val altitudeFactor = if (units == MeasurementUnits.IMPERIAL) 3.28084 else 1.0
    val altitudeUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_FT else UNIT_M
    val totalDistanceKm = points.last().distanceKm.let { if (it > 0.0) it else 1.0 }

    return ElevationChartUi(
        points = points.map { ElevationPointUi(it.distanceKm, it.altitudeM) },
        maxAltitudeLabel = String.format(Locale.US, "$FORMAT_NO_DECIMALS $altitudeUnit", maxAltitudeM * altitudeFactor),
        maxAltitudeDistanceFraction = (maxAltitudeDistanceKm / totalDistanceKm).toFloat().coerceIn(0f, 1f),
        minAltitudeLabel = String.format(Locale.US, "$FORMAT_NO_DECIMALS $altitudeUnit", minAltitudeM * altitudeFactor),
        minAltitudeDistanceFraction = (minAltitudeDistanceKm / totalDistanceKm).toFloat().coerceIn(0f, 1f),
    )
}
```

Also add `RideDetailState.elevationChart`:

```kotlin
data class RideDetailState(
    val ride: RideDetailUi? = null,
    val laps: List<RideLapUi> = emptyList(),
    val trackPoints: List<TrackPointUi> = emptyList(),
    val elevationChart: ElevationChartUi? = null,
    val isLoading: Boolean = true,
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest --tests "com.speedevand.inkride.history.presentation.RideRecordMappingTest"`
Expected: PASS (all tests including the 2 new ones)

- [ ] **Step 5: Commit**

```bash
git add feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailContract.kt \
        feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideRecordMappingTest.kt
git commit -m "Add ElevationChartUi mapping to RideDetailContract"
```

---

### Task 6: Wire the profile into `RideDetailViewModel`

**Files:**
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailViewModel.kt`
- Test: `feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `buildElevationProfile` (Task 4), `ElevationProfile.toChartUi` (Task 5), `RideDetailState.elevationChart` (Task 5).

- [ ] **Step 1: Write the failing tests**

Add to `RideDetailViewModelTest.kt` (new imports: `assertk.assertions.isNull`):

```kotlin
    @Test
    fun `track points with altitude populate the elevation chart`() =
        runTest {
            rideRepo.setRide(sampleRide)
            trackPointRepo.points =
                listOf(
                    RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                    RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = 150.0),
                )
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.elevationChart).isNotNull()
        }

    @Test
    fun `track points without altitude leave the elevation chart null`() =
        runTest {
            rideRepo.setRide(sampleRide)
            trackPointRepo.points =
                listOf(
                    RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0),
                    RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0),
                )
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.elevationChart).isNull()
        }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest --tests "com.speedevand.inkride.history.presentation.RideDetailViewModelTest"`
Expected: FAIL — `viewModel.state.value.elevationChart` doesn't resolve (state has no such field wired yet / always null even for the "populated" case)

- [ ] **Step 3: Write the implementation**

In `RideDetailViewModel.kt`, replace the track-points block inside the `init` block:

```kotlin
                    val trackPoints =
                        trackPointRepository
                            .getPoints(rideId)
                            .let { result ->
                                when (result) {
                                    is com.speedevand.inkride.core.domain.Result.Success -> {
                                        result.data.map { point -> TrackPointUi(point.latitude, point.longitude) }
                                    }

                                    is com.speedevand.inkride.core.domain.Result.Error -> {
                                        emptyList()
                                    }
                                }
                            }
```

with:

```kotlin
                    val rawTrackPoints =
                        trackPointRepository
                            .getPoints(rideId)
                            .let { result ->
                                when (result) {
                                    is com.speedevand.inkride.core.domain.Result.Success -> result.data
                                    is com.speedevand.inkride.core.domain.Result.Error -> emptyList()
                                }
                            }
                    val trackPoints = rawTrackPoints.map { point -> TrackPointUi(point.latitude, point.longitude) }
                    val elevationProfile = buildElevationProfile(rawTrackPoints)
```

Then update the `_state.update` block — replace:

```kotlin
                    userSettingsRepository.observeSettings().collect { settings ->
                        _state.update {
                            it.copy(
                                ride = ride.toDetailUi(settings.units),
                                laps = laps.map { lap -> lap.toLapUi(settings.units) },
                                trackPoints = trackPoints,
                                isLoading = false,
                            )
                        }
                    }
```

with:

```kotlin
                    userSettingsRepository.observeSettings().collect { settings ->
                        _state.update {
                            it.copy(
                                ride = ride.toDetailUi(settings.units),
                                laps = laps.map { lap -> lap.toLapUi(settings.units) },
                                trackPoints = trackPoints,
                                elevationChart = elevationProfile?.toChartUi(settings.units),
                                isLoading = false,
                            )
                        }
                    }
```

Add the import: `com.speedevand.inkride.core.domain.tracking.buildElevationProfile`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest --tests "com.speedevand.inkride.history.presentation.RideDetailViewModelTest"`
Expected: PASS (all tests including the 2 new ones)

- [ ] **Step 5: Commit**

```bash
git add feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailViewModel.kt \
        feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideDetailViewModelTest.kt
git commit -m "Compute the elevation chart alongside track points in RideDetailViewModel"
```

---

### Task 7: `ElevationChart` composable + screen wiring

**Files:**
- Create: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/ElevationChart.kt`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailScreen.kt`
- Modify: `feature/history/presentation/src/main/res/values/strings.xml`
- Modify: `feature/history/presentation/src/main/res/values-pl/strings.xml`

**Interfaces:**
- Consumes: `ElevationChartUi` (Task 5), `RideDetailState.elevationChart` (Task 5).

This task has no new automated test (no Compose UI test infra exists for this screen beyond what Task 5/6 already cover at the data level) — verification is the manual check in Step 5.

- [ ] **Step 1: Create the composable**

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A static, monochrome altitude-vs-distance line for a ride. Drawn once per
 * composition with no animation or gesture handling, consistent with the
 * app's E-Ink display constraint.
 */
@Composable
fun ElevationChart(
    chart: ElevationChartUi,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    val minAltitudeM = chart.points.minOf { it.altitudeM }
    val maxAltitudeM = chart.points.maxOf { it.altitudeM }
    val altitudeRangeM = (maxAltitudeM - minAltitudeM).let { if (it > 0.0) it else 1.0 }
    val maxDistanceKm = chart.points.last().distanceKm.let { if (it > 0.0) it else 1.0 }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(120.dp),
    ) {
        val path = Path()
        chart.points.forEachIndexed { index, point ->
            val x = (point.distanceKm / maxDistanceKm).toFloat() * size.width
            val y = size.height - ((point.altitudeM - minAltitudeM) / altitudeRangeM).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 3.dp.toPx()))

        drawAltitudeLabel(chart.maxAltitudeLabel, chart.maxAltitudeDistanceFraction, color, nearTop = true)
        drawAltitudeLabel(chart.minAltitudeLabel, chart.minAltitudeDistanceFraction, color, nearTop = false)
    }
}

private fun DrawScope.drawAltitudeLabel(
    text: String,
    xFraction: Float,
    color: Color,
    nearTop: Boolean,
) {
    val labelMarginPx = 24.dp.toPx()
    val x = (xFraction * size.width).coerceIn(labelMarginPx, size.width - labelMarginPx)
    val y = if (nearTop) 14.dp.toPx() else size.height - 4.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText(
        text,
        x,
        y,
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.textSize = 12.sp.toPx()
        },
    )
}
```

- [ ] **Step 2: Add the string resources**

In `feature/history/presentation/src/main/res/values/strings.xml`, add after `ride_detail_avg_power` (line 41), before the `<!-- Route map -->` comment:

```xml
    <string name="ride_detail_section_elevation_chart">Elevation</string>
```

In `feature/history/presentation/src/main/res/values-pl/strings.xml`, same position:

```xml
    <string name="ride_detail_section_elevation_chart">Wysokość</string>
```

- [ ] **Step 3: Wire the section into `RideDetailScreen.kt`**

Update the "Additional" section's `showDivider` (it must now also account for the new section being empty/non-empty) — replace:

```kotlin
                RideDetailSection(
                    title = stringResource(R.string.ride_detail_section_additional),
                    showDivider = state.trackPoints.isNotEmpty() || state.laps.isNotEmpty(),
                ) {
```

with:

```kotlin
                RideDetailSection(
                    title = stringResource(R.string.ride_detail_section_additional),
                    showDivider = state.elevationChart != null || state.trackPoints.isNotEmpty() || state.laps.isNotEmpty(),
                ) {
```

Then insert a new section immediately after the "Additional" section's closing `}` and before the `if (state.trackPoints.isNotEmpty())` Route block:

```kotlin
                if (state.elevationChart != null) {
                    RideDetailSection(
                        title = stringResource(R.string.ride_detail_section_elevation_chart),
                        showDivider = state.trackPoints.isNotEmpty() || state.laps.isNotEmpty(),
                    ) {
                        ElevationChart(
                            chart = state.elevationChart,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

```

- [ ] **Step 4: Build and lint**

Run: `./gradlew :feature:history:presentation:assembleDebug ktlintCheck`
Expected: PASS

- [ ] **Step 5: Manual verification**

Run: `./gradlew :app:installDebug`

Open the app, go to Ride History, open a ride that has GPS track data recorded with altitude (any ride recorded after Priority-1 GPX tracking shipped). Confirm:
- An "Elevation" section appears between "Additional Stats" and "Route" (or "Laps" if no route).
- The line is a single static black line, no animation.
- Two labels show altitude near the peak and trough of the line, in the unit matching Settings → Units.
- Opening a ride with no track points (or one predating altitude recording) shows no "Elevation" section at all.

- [ ] **Step 6: Commit**

```bash
git add feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/ElevationChart.kt \
        feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailScreen.kt \
        feature/history/presentation/src/main/res/values/strings.xml \
        feature/history/presentation/src/main/res/values-pl/strings.xml
git commit -m "Show the elevation chart in RideDetailScreen"
```
