# Quality & Tech Debt Round 2 — Design

Date: 2026-07-08

## Context

The prior stability round (`2026-07-08-critical-stability-ci-fixes-design.md`) explicitly deferred
two items as "separate tech debt rounds": empty-module cleanup and test-coverage gaps
(`RideTracker`, `AndroidRideSensorDataSource`, DB migrations). This round picks those up, plus a
fresh read-through of the tracking/history/persistence code turned up three real correctness bugs
worth fixing alongside the cleanup.

Six independent, low-to-medium-risk items, bundled into one round for the same reason as the prior
one: each is small and self-contained, not because they share implementation. Each can be
implemented and reviewed as its own commit.

## Out of scope

- BLE auto-reconnect — still a bigger UX topic (retry/backoff, re-pairing flow), still deferred.
- New features — separate design(s) if/when prioritized.
- Retroactively reconstructing Room schema history for versions 1–3 — no path to do this
  correctly from the current codebase (see item 5).

## 1. Undo-delete permanently loses GPS track and laps

**Problem:** `RideHistoryViewModel.OnDeleteRide` (lines 54-71) caches only the `RideRecord`
(summary row) before calling `rideHistoryRepository.deleteById()`. `ride_track_point` and
`ride_lap` both have `onDelete = CASCADE` foreign keys to `ride_history`
(`RideTrackPointEntity.kt`, `RideLapEntity.kt`), so the delete cascades and wipes them too.
`OnUndoDelete` (lines 73-82) only calls `rideHistoryRepository.save(ride)`, re-inserting the
summary row — the track points and laps never come back. Since the ride-detail elevation chart and
GPX export both depend on track points, the user-visible effect is: delete a ride, tap "Undo," the
ride reappears in history with correct stats but an empty elevation chart and no GPX to export,
permanently.

**Change:**
- `RideHistoryViewModel`: inject `RideTrackPointRepository` and `RideLapRepository` (both already
  exist and are used by `RideDetailViewModel` — `getPoints`/`savePoints` and
  `getLaps`/`saveLaps`).
- `OnDeleteRide`: alongside caching the `RideRecord`, also fetch and cache
  `trackPointRepository.getPoints(id)` and `lapRepository.getLaps(id)` before calling
  `deleteById`. Bundle all three into a small private holder (e.g. a `DeletedRideBundle` data
  class) instead of the current bare `recentlyDeletedRide: RideRecord?`.
- `OnUndoDelete`: call `rideHistoryRepository.save(ride)` first; on success, use the **returned
  row id** (not the stale `ride.id`) to call `trackPointRepository.savePoints(newId, points)` and
  `lapRepository.saveLaps(newId, laps)`. Relying on `INSERT OR REPLACE` happening to preserve the
  original id would be an implementation-detail coincidence, not a contract — `save()`'s own doc
  comment says it "returns the generated row id on success," so that's the value to use.
- If either restore call fails, surface it via the existing `ShowError` event — the ride row itself
  is already restored at that point, so a partial failure means "ride is back, track/laps may be
  incomplete," not a full rollback.

**Testing:** unit test on `RideHistoryViewModel` — delete a ride with track points and laps, undo,
assert `trackPointRepository`/`lapRepository` were called with the restored id and the original
points/laps.

## 2. GPS bounce detection inflates distance on the outbound leg

**Problem:** `RideMetricsCalculator`'s bounce detection (around lines 225-249) looks at the
*current* fix vs. the fix from 3 samples ago: if the middle fix jumped far away
(`jumpDist > bounceJumpRadiusM`) and the current fix returns close to the original position
(`returnDist < bounceReturnRadiusM`), the *current* segment's distance is zeroed as a bounce
return. But the outbound jump itself (the segment ending at the "middle" fix) was already added to
`totalDistanceM` on the previous call, as long as it didn't independently trip
`isSpeedOutlier`/`isAccelOutlier`/cross-validation — which a ~30-35m jump over 2-3 seconds (e.g.
after a brief GPS dropout in an urban canyon or tunnel) easily avoids, since `maxPlausibleSpeedMps`
is 40 m/s. Net effect: `totalDistanceM` is permanently inflated by the outbound jump distance,
with no correction. The existing test
(`RideMetricsCalculatorTest.kt:420-443`, "bounce detection rejects return leg of GPS jump-bounce")
already has a comment acknowledging this gap ("But A→B was already processed... We verify the
calculator didn't crash") without asserting the net distance is corrected — in that specific test
the outbound leg happens to trip `isSpeedOutlier` on its own (555 m/s), so the test doesn't
currently exercise the actual bug.

**Change:**
- Extend `recentPositions` (currently `ArrayDeque<Pair<Double, Double>>`) to also track the
  distance credited to `totalDistanceM` for each accepted fix, e.g.
  `ArrayDeque<Triple<Double, Double, Double>>` (lat, lon, distanceAddedM).
- When a bounce is confirmed, subtract the outbound jump's previously-added distance from
  `totalDistanceM` (in addition to zeroing the current return leg's distance), floored at 0 to
  guard against accumulated floating-point drift pushing it negative.
- Strengthen the existing bounce test to assert the corrected `distanceKm` directly (not just that
  `gpsQuality` is non-null), and add a new test case using a jump that does *not* independently
  trip the speed/accel outlier checks (i.e. a realistic dropout-then-bounce), to actually exercise
  the bug this item fixes.

**Testing:** updated/new unit tests in `RideMetricsCalculatorTest.kt` asserting `distanceKm`
before/after a jump-bounce sequence equals the distance from genuine movement only.

## 3. BLE/GPS state race in RideTracker

**Problem:** In `RideTracker`, the GPS-sample handler (lines 369-406) reads `latestBle` into a
local snapshot and merges it into a locally-computed `metrics` object, which is then written
wholesale via `_state.updateAndGet { current -> current.copy(..., metrics = metrics, ...) }`
(lines 401-406) — replacing `current.metrics` entirely. The concurrent `bleJob` (lines 345-368)
commits new HR/cadence values into `_state` independently as BLE notifications arrive. If `bleJob`
commits a newer value between the GPS handler's read of `latestBle` and its own `updateAndGet`
call, that newer value is overwritten by the GPS handler's stale snapshot. Self-corrects on the
next GPS fix (~1s later), so this is a transient flicker, not data loss — lowest severity of the
three bugs, included because the fix is small and low-risk.

**Change:**
- Inside the GPS handler's `updateAndGet` lambda, stop merging the externally-captured `ble`
  snapshot into `metrics` before the atomic update. Instead, build `metrics` from `baseMetrics`
  (GPS-only, from `metricsCalculator.process`) and read `current.metrics.heartRateBpm` /
  `current.metrics.cadenceRpm` at commit time inside the lambda — since `updateAndGet` always
  retries against the latest `current` on CAS failure, this guarantees the freshest value
  `bleJob` has committed is never clobbered.

**Testing:** if feasible in isolation, a `RideTrackerTest` case that interleaves a GPS sample and a
BLE sample and asserts the BLE value survives regardless of ordering; otherwise cover via the
existing `RideTrackerTest.kt` patterns for BLE state propagation.

## 4. Empty module cleanup

**Problem:** `:core:data`, `:feature:dashboard:domain`, and `:feature:dashboard:data` each contain
only a `build.gradle.kts` — no `src/` directory at all — and nothing in `:app` or any other module
depends on them (confirmed via grep across all `build.gradle.kts` files).

**Change:**
- Delete the three module directories.
- Remove their `include(...)` lines from `settings.gradle.kts`.

**Testing:** `./gradlew build` succeeds with no dangling references.

## 5. Room schema export and migration tests

**Problem:** `AppDatabase.kt` declares `exportSchema = false`, even though the `inkride.room`
convention plugin already configures a schema directory (`$projectDir/schemas`) that has never
been populated. Combined with `.fallbackToDestructiveMigration()` alongside two hand-written
migrations (`MIGRATION_4_5`, `MIGRATION_5_6` in `DatabaseModule.kt`), Room never validates that a
migration actually produces the schema the current `@Entity` classes declare, and there is no way
to write a real `MigrationTestHelper`-based test today (it requires exported schema JSON for both
the "from" and "to" versions). A migration bug — especially in `MIGRATION_5_6`'s data-seeding
logic (copying the flat bike-weight/type columns into a new `bike_profile` row and pointing
`activeBikeProfileId` at it) — would only surface as a crash or silent data loss on a real device.

**Change:**
- Set `exportSchema = true` on `AppDatabase`. This captures the current (v6) schema now and every
  future migration from this point forward gets full `MigrationTestHelper` coverage. Commit the
  generated schema JSON under `core/database/schemas/`.
- For the two *existing* migrations, there's no historical schema JSON for v4/v5 to reconstruct
  reliably from the current codebase, so full `MigrationTestHelper` validation of them is out of
  reach without risk of getting a hand-reconstructed historical schema subtly wrong. Instead, add
  manual migration tests: build the pre-migration table schema by hand via raw SQL (reconstructed
  from the migration's own `CREATE TABLE`/`ALTER TABLE` statements, which fully describe what
  changed), insert representative rows, run `MIGRATION_4_5.migrate(db)` /
  `MIGRATION_5_6.migrate(db)` against a raw `SupportSQLiteDatabase`, and assert the resulting rows
  via raw queries — with particular attention to `MIGRATION_5_6`'s `bike_profile` seeding and
  `activeBikeProfileId` backfill, the riskiest logic of the two.

**Testing:** the manual migration tests described above, plus confirming
`./gradlew :core:database:assembleDebug` succeeds with schema export enabled and produces a
`6.json` file.

## 6. Sensor data-source testability

**Problem:** `AndroidRideSensorDataSource.kt` (429 lines) mixes real logic — a circular-EMA heading
smoothing filter, true-north correction, and throttling emitted headings to ~2° steps — directly
into `LocationManager`/`SensorManager` callback plumbing tied to `Context`. (Altitude fusion
between barometer and GPS is *not* in scope here — that already lives in
`RideMetricsCalculator.fusedAltitude()` and is already tested; this file only does a one-line
`SensorManager.getAltitude()` unit conversion on the raw pressure reading.) There's no seam to
unit test the heading logic, and it currently has zero test coverage. The project already has an
established pattern for exactly this problem: `ElevationProfileBuilder`, `CaloriesEstimator`, and
`PowerEstimator` all started as logic embedded in framework-facing classes and were extracted into
pure, independently-testable classes in `:core:domain`.

**Change:**
- Extract the pure heading-smoothing/throttling math (circular EMA filter, true-north correction,
  2°-step emission throttle) out of `AndroidRideSensorDataSource` into a new small class in
  `core/domain/.../tracking` (e.g. `HeadingSmoother`), following the existing calculator pattern:
  a plain Kotlin class taking raw inputs and returning the smoothed/throttled output, no Android
  dependencies.
- `AndroidRideSensorDataSource` keeps the `SensorEventListener` callback registration and feeds raw
  readings into the extracted class, using its output to build `RideSensorSample`.
- The `LocationManager`/`SensorManager` registration/lifecycle code itself stays as an untested
  thin wrapper, consistent with how the rest of the tracking data-source boundary already works
  (`RideMetricsCalculator` is tested in isolation; `RideTracker`'s orchestration around it is
  tested separately at a coarser grain).

**Testing:** new unit tests for the extracted `HeadingSmoother` covering: EMA smoothing behavior,
the 2° emission threshold, and true-north declination correction.

## Rollout

All six items are independent and can land as separate commits within one branch/PR, or as
separate small PRs — implementer's choice at plan time. No new permissions. Item 5 changes the
`AppDatabase` schema-export setting (build-time only, no runtime/user-facing effect) and adds a new
committed `schemas/` directory. No other user-facing data changes.
