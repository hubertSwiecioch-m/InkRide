# Elevation Chart in Ride Detail — Design

Date: 2026-07-08

## Context

A full-project audit found `FEATURE_PLAN.md`'s Priority 1–4 backlog fully implemented, with a
short list of never-designed feature ideas noted in passing (elevation chart, BLE power meter,
wheel-speed distance, segments/PRs). This spec covers the first of those: a post-ride
altitude-over-distance chart in `RideDetailScreen`, in the style of a standard cycling elevation
profile (Strava/Komoot-like), rendered E-Ink-appropriately (static, monochrome, no
animation/interactivity).

`RideTrackPointEntity`/`RideTrackPoint` already store per-point altitude (nullable — fused
barometer/GPS, per `RideMetricsCalculator`), and `RideDetailViewModel` already loads the full
track-point list for the existing OsmDroid route map. This feature reuses that data; no new
recording-side or database changes are needed.

## Out of scope

- BLE power meter, wheel-speed/CSC distance, segments/PRs — separate features, separate specs.
- The other items flagged by the same audit (empty-module cleanup, test-coverage gaps, the
  destructive-migration-fallback risk, BLE auto-reconnect) — unrelated, tracked separately.
- Interactive chart features (tap-to-scrub, tooltips, pinch-zoom) — deliberately excluded; E-Ink
  favors static renders over gesture-driven redraws.

## 1. Shared haversine utility (prerequisite cleanup)

**Problem:** Two private, near-identical haversine implementations already exist —
`RouteFollower.kt:190` (`haversine`) and `RideMetricsCalculator.kt:570`
(`haversineDistanceMeters`, literal `6_371_000.0`). The new elevation-profile builder needs the
same calculation to turn consecutive lat/lng pairs into cumulative distance; adding a third copy
would be the wrong move.

**Change:**
- Extract one public function into `:core:domain` (e.g.
  `com.speedevand.inkride.core.domain.tracking.GeoDistance.metersBetween(lat1, lon1, lat2, lon2)`
  or a top-level function in a new `GeoDistance.kt`).
- `RouteFollower` and `RideMetricsCalculator` call the shared function instead of their private
  copies; their private implementations are deleted. No behavior change (same formula, same
  Earth-radius constant).

**Testing:** existing `RouteFollowerTest`/`RideMetricsCalculator` tests must keep passing
unchanged (regression check that the extraction is behavior-preserving); no new test required for
the utility itself beyond what those suites already exercise indirectly — though a small direct
unit test (two known coordinates → known distance) is cheap and worth adding.

## 2. `ElevationProfileBuilder` (new pure calculator)

**Problem:** No existing code turns a raw track-point list into a chartable, distance-indexed
altitude series. Track points can number in the tens of thousands for a multi-hour ride (GPS
sampled ~1/sec), so the raw series is both too dense to render efficiently on a Compose `Canvas`
and too jittery (barometer/GPS noise) to look clean as a line.

**Change:** new file `core/domain/.../tracking/ElevationProfileBuilder.kt`:

```kotlin
data class ElevationProfilePoint(val distanceKm: Double, val altitudeM: Double)

data class ElevationProfile(
    val points: List<ElevationProfilePoint>, // downsampled, for rendering the line
    val minAltitudeM: Double,                 // from the FULL series, not downsampled
    val maxAltitudeM: Double,
    val minAltitudeDistanceKm: Double,        // where the min occurs, for label placement
    val maxAltitudeDistanceKm: Double,
)

fun buildElevationProfile(
    points: List<RideTrackPoint>,
    maxSamples: Int = 200,
): ElevationProfile?
```

- Filters `points` to those with non-null `altitudeM`. Returns `null` if fewer than 2 remain (no
  usable profile).
- Computes cumulative distance-into-ride at each valid point via the shared haversine utility
  (§1), skipping any point pair whose distance can't be computed (shouldn't happen once filtered).
- Finds min/max altitude **and their distance position** over the full filtered series first —
  this is the source of truth for the labels, computed before any downsampling, so a peak can
  never be lost or mis-positioned by the decimation step below.
- Downsampling: splits the full filtered series into up to `maxSamples` equal-width buckets by
  distance; each bucket's output point is `(midpoint distance, average altitude of points in
  bucket)`. This smooths GPS/barometer jitter as a side effect. If the filtered series already has
  ≤ `maxSamples` points, no bucketing occurs (returned as-is).

**Testing:** new `ElevationProfileBuilderTest` in `:core:domain`, covering: fewer than 2 valid
points → `null`; all-null-altitude series → `null`; null altitude points interspersed with valid
ones are excluded from distance accumulation; min/max and their distances match known input
regardless of `maxSamples`; downsampled point count is capped at `maxSamples` for a large series;
a series smaller than `maxSamples` is returned unbucketed.

## 3. Wire into `RideDetailViewModel` / `RideDetailContract`

**Change:** `RideDetailContract.kt` follows its existing split — `TrackPointUi` holds raw numeric
fields for geometry, `RideDetailUi` holds pre-formatted unit-aware strings for display. The
elevation chart needs both (raw points to draw, formatted labels to show), so it gets its own UI
type combining the two:

```kotlin
data class ElevationChartUi(
    val points: List<ElevationPointUi>,     // metric distanceKm/altitudeM — canvas only needs relative position, not display units
    val maxAltitudeLabel: String,            // unit-aware, e.g. "312 m" / "1024 ft"
    val maxAltitudeDistanceFraction: Float,  // 0f..1f, precomputed x-position for the label
    val minAltitudeLabel: String,
    val minAltitudeDistanceFraction: Float,
)

data class ElevationPointUi(val distanceKm: Double, val altitudeM: Double)

fun ElevationProfile.toChartUi(units: MeasurementUnits = MeasurementUnits.METRIC): ElevationChartUi
```

- `RideDetailState` gains `elevationChart: ElevationChartUi? = null`.
- `RideDetailViewModel.kt`: in the existing init block where track points are loaded (lines
  ~44–57), call `buildElevationProfile(trackPoints)` once, map it through `toChartUi(units)`, and
  set it on state alongside the existing `TrackPointUi` mapping. No new repository call — reuses
  the track points already fetched for the route map. `null` (from `buildElevationProfile`) maps
  straight through to `elevationChart = null`.

**Testing:** extend `RideDetailViewModelTest` with a case asserting `elevationChart` is populated
when track points carry altitude, and stays `null` when they don't (mirrors the existing
empty-state assertions for `trackPoints`). Add a focused test for `toChartUi` covering the
metric/imperial label conversion, matching the existing `toLapUi`/`toDetailUi` test style.

## 4. `ElevationChart` composable + screen wiring

**Change:**
- New private composable in `RideDetailScreen.kt`, `ElevationChart(chart: ElevationChartUi,
  modifier: Modifier)`:
  - Draws a single monochrome polyline via Compose `Canvas`/`drawPath`, normalizing
    `chart.points`' `distanceKm`/`altitudeM` to the canvas bounds (line-only style — no filled
    area, matching the approved visual direction: lowest ink coverage, cleanest E-Ink refresh).
  - Draws `chart.maxAltitudeLabel`/`chart.minAltitudeLabel` as static text at their precomputed
    `*DistanceFraction` x-positions — already unit-converted by `toChartUi`, so the composable does
    no unit math itself.
  - No animation, no gesture handling, no interactivity — a single static draw per composition,
    consistent with the E-Ink constraint.
- `RideDetailScreen.kt`: new "Elevation" section using the existing private `RideDetailSection`
  header helper (Column + `TextMMD` title + `HorizontalDividerMMD`, same as the other sections),
  placed after "Additional" (which already shows elevation gain/calories/power) and before
  "Route". Gated on `state.elevationChart != null` — mirrors the Route section's existing pattern
  of omitting the whole section (not showing an empty chart) when there's no usable data.
- New string resource for the section title (`ride_detail_elevation_chart` or similar) in
  `strings.xml` and `values-pl/strings.xml`, following the project's existing localization
  convention.

**Testing:** no dedicated Compose UI test infra exists for this screen beyond what's already
there; rely on the `RideDetailViewModelTest` coverage (§3) for the data path and a manual check
(`./gradlew :app:installDebug`, open a ride with GPS track data) for the visual result, consistent
with how the existing route map's rendering was verified.

## Rollout

Four ordered, dependent pieces (haversine extraction → profile builder → ViewModel wiring →
composable/screen), unlike the previous "critical stability" round's independent items — this
lands as a single PR/branch since each step depends on the previous one. No new permissions, no
DB schema change, no new dependencies (Compose `Canvas` is already available).
