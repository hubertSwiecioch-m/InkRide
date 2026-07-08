# GPS & Sensor Precision — Design

Date: 2026-07-08

## Context

The prior stability/quality rounds (`2026-07-08-critical-stability-ci-fixes-design.md`,
`2026-07-08-quality-tech-debt-round-2-design.md`) fixed real correctness bugs in the tracking
pipeline (undo-delete data loss, GPS bounce distance inflation, a BLE/GPS state race, missing
migration coverage). This round is different in kind: it's a precision/accuracy audit of the
sensor and GPS processing pipeline — the whole "bike computer" functionality (GPS, heading, BLE
heart rate/cadence, calories, power, elevation) — benchmarked against publicly documented
practices used by consumer sports GPS devices (Garmin, Strava, Suunto) and the relevant BLE SIG
specs, with the goal of matching or exceeding that class of device.

The audit (full code survey + industry-practice research) found the existing pipeline is
considerably more sophisticated than a naive implementation: `RideMetricsCalculator` already
implements a barometer/GPS complementary altitude filter, GPS cold-start warm-up gating, bounce/
jump detection with distance reversal, cross-validation between GPS Doppler speed and position-
delta speed, and stationary-drift hysteresis. `CaloriesEstimator` uses a legitimate ACSM MET-based
model; `PowerEstimator` uses a genuine physics model (rolling resistance + aero drag + gravity +
drivetrain loss) and is honest in its own KDoc about its ±30-60% accuracy ceiling. None of that is
being reworked here.

Seven independent items, six of which are concrete changes and one of which is a documented
decision *not* to change something (kept as an item so the reasoning isn't re-litigated in a
future audit). Same bundling rationale as round 2: each item is small, self-contained, and
independently landable, not because they share implementation.

## Out of scope

- Reworking `CaloriesEstimator`, `PowerEstimator`'s core formulas, or the existing barometer/GPS
  altitude complementary filter — these already match or exceed documented industry practice.
- Removing/replacing the existing `RideMetricsCalculator` outlier-rejection heuristics (speed/
  accel outlier checks, bounce detection, cross-validation, stationary hysteresis) — see item 7,
  they remain as a secondary safety net on top of the new Kalman-filtered input.
- Zone-time-in-ride breakdown (time spent in each HR zone per ride, shown in ride history/detail)
  — a bigger feature requiring schema and ride-detail UI changes; deferred, see item 4.
- HR-reserve/Karvonen zones (requires a new user-entered resting-HR field, DB migration, and
  settings UI) — the simpler Tanaka %HRmax method was chosen instead since it needs no new user
  input (see item 4).
- BLE auto-reconnect — still out of scope per round 2, unrelated to this audit.

## 1. Heading: switch to `TYPE_ROTATION_VECTOR`

**Problem:** `AndroidRideSensorDataSource` derives azimuth from a raw accelerometer +
magnetometer pair (`SensorManager.getRotationMatrix`/`getOrientation`, lines ~139-172), not from
the gyro-fused rotation vector sensor. This is more susceptible to noise from bumps/braking/
vibration transmitted through the bike frame than a gyro-fused estimate would be — `HeadingSmoother`
(the extracted EMA/throttle filter, `core/domain/.../HeadingSmoother.kt`) has to work harder to
smooth out that extra noise.

**Change:**
- Register `Sensor.TYPE_ROTATION_VECTOR` instead of `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD`.
- Derive the rotation matrix via `SensorManager.getRotationMatrixFromVector` instead of
  `getRotationMatrix`, then `getOrientation` as today.
- Keep everything downstream unchanged: true-north declination correction
  (`GeomagneticField`), `HeadingSmoother`'s circular EMA + 2° emission throttle, and the
  GPS-course-over-ground handoff above `gpsBearingMinSpeedMps` (2.0 m/s) all operate on the
  resulting azimuth exactly as they do today — `HeadingSmoother` doesn't care what sensor produced
  its input.
- `onAccuracyChanged` gating (suppressing heading while `SENSOR_STATUS_UNRELIABLE`/`_LOW`) is
  registered against the rotation vector sensor instead of the magnetometer; the same accuracy
  enum applies to `TYPE_ROTATION_VECTOR`.

**Testing:** `HeadingSmootherTest` is untouched and remains valid (it doesn't depend on which raw
sensor feeds it). Manually verify on a device that heading still responds correctly at rest and in
motion, and that the accuracy-unreliable suppression still triggers appropriately (e.g. right after
app start before the fusion has converged).

## 2. Cadence: 3-second dropout timeout

**Problem:** `latestCadence` in `RideTracker`/`AndroidBleSensorDataSource` is only cleared on GATT
disconnect. Most CSC sensors stop sending notifications entirely when the crank stops turning
(rather than sending an explicit 0 rpm), so today cadence freezes at its last nonzero value
instead of dropping to 0 when the rider stops pedaling — a real gap vs. devices like Garmin, which
zero out cadence/power after a few seconds of inactivity.

**Change:**
- Track `lastCadenceSampleAtMs` in `RideTracker` alongside `latestBle`.
- Add a lightweight ticker coroutine (~1s interval) that checks elapsed time since
  `lastCadenceSampleAtMs`; if it exceeds 3000ms, commit `cadenceRpm = 0` into `_state` (via the
  same `updateAndGet` pattern already used for BLE state, to stay consistent with the race fix
  from round 2).
- Heart rate is explicitly out of scope for this timeout — HRM sensors send continuously as long
  as a heartbeat is detected, so there's no equivalent "sensor went quiet because nothing is
  happening" case to guard against.

**Testing:** new `RideTrackerTest` case using a `TestDispatcher`/virtual time — advance time >3s
past the last cadence sample with no new BLE notification, assert `state.metrics.cadenceRpm`
becomes 0; assert a fresh cadence sample before the timeout keeps the real value.

## 3. Heart-rate outlier rejection

**Problem:** `BleGatt.parseHeartRate()` correctly decodes the GATT Heart Rate Measurement
characteristic (8/16-bit BPM per the flags byte), but every successfully-parsed value — including
physiologically implausible ones (0 bpm from a glitch, a single corrupted-byte spike to 250+) —
flows straight through to `RideTracker` state, alerts, and the UI with no validation.

**Change:**
- New pure class `HeartRateFilter` in `core/domain/.../tracking`, mirroring the `HeadingSmoother`
  extraction pattern (plain Kotlin, no Android dependencies, independently testable).
- Rejects a reading if it falls outside a physiological bound (30-220 bpm) or if it implies a
  rate-of-change from the last accepted reading that exceeds a plausible physiological limit
  (heart rate can't jump by dozens of bpm between consecutive ~1s BLE notifications).
- A rejected sample is dropped (the last accepted value is retained) rather than being clamped or
  interpolated — consistent with how the rest of the pipeline treats an implausible input as "not
  usable" rather than trying to correct it.
- `BleGatt.parseHeartRate()` itself is unchanged — it stays a pure protocol decoder. The filter is
  applied where `BleSample`s are consumed (`RideTracker`'s `bleJob`), so `BleGattTest` (parsing
  correctness) and the new filter test stay cleanly separated by responsibility.

**Testing:** new `HeartRateFilterTest` — accepts a normal in-range reading, rejects 0 and 250+,
rejects an implausible jump between consecutive readings, accepts a fast-but-plausible change
(e.g. a hard effort spike).

## 4. Heart-rate zones (Tanaka %HRmax)

**Problem:** the app only has flat min/max BPM alert thresholds (`AlertConfig.hrZoneMinBpm/
hrZoneMaxBpm`, despite the field names — they're single thresholds, not zones). No zone concept
exists.

**Change:**
- New pure class `HeartRateZoneCalculator` in `core/domain/.../tracking`.
- `HRmax = 208 − 0.7 × age` (Tanaka formula — more accurate than the older 220−age rule of thumb),
  using the existing `UserSettings.age` field. No new user-entered field, no DB migration.
- Five standard zones as %HRmax bands: Z1 50-60%, Z2 60-70%, Z3 70-80%, Z4 80-90%, Z5 90%+.
- Expose the rider's current zone (1-5) as a derived value alongside live BPM on the dashboard.
  Karvonen/HRR was considered and rejected for this round — it needs a resting-HR field the app
  doesn't collect today (new settings field + migration), which is disproportionate to the value
  over Tanaka for a first cut; see "Out of scope."
- Zone-time-in-ride breakdown (e.g. "32 min in Z3") is a separate, larger feature (ride-detail UI
  + persistence) — deferred, see "Out of scope."

**Testing:** new `HeartRateZoneCalculatorTest` — HRmax for a few representative ages, correct zone
assignment at band boundaries.

## 5. Calories: no change (documented decision)

**Problem considered:** industry sources generally rate power-based calorie calculation
(`kcal ≈ kJ × 4.35`, accounting for ~23% human gross efficiency) as more accurate than MET-based
estimates *when power comes from a real power meter*. The app already computes an estimated power
via `PowerEstimator`.

**Decision: do not add power-based calories.** `PowerEstimator`'s own KDoc documents a ±30-60%
accuracy ceiling on its physics-based estimate (dominated by unmeasured wind). Deriving calories
from that estimate would compound one uncertain estimate on top of another, likely making the
calorie figure *less* reliable than the existing ACSM MET-based model, not more — the literature's
"power beats MET" conclusion assumes measured power, which doesn't apply here. This is recorded as
a deliberate decision so it isn't flagged as an oversight in a future audit.

**Change:** none. `CaloriesEstimator` is untouched.

## 6. Altitude-adjusted air density in `PowerEstimator`

**Problem:** `PowerEstimator`'s aerodynamic drag term uses a fixed air density of 1.225 kg/m³
regardless of elevation. Air density drops measurably at altitude, reducing aero drag — relevant
on sustained climbs, where the app already has a reliable fused altitude available.

**Change:**
- Add `airDensityAt(altitudeM: Double): Double` using the standard ISA approximation:
  `ρ(h) = 1.225 × (1 − 2.25577e-5 × h)^5.25588`.
- Feed it `metrics.altitudeM` (the already-computed barometer/GPS fused altitude from
  `RideMetricsCalculator.fusedAltitude()`) instead of the current constant, in the aero drag term
  only.

**Testing:** extend `PowerEstimatorTest` — same speed/grade at a higher altitude yields lower
estimated power than at sea level, magnitude consistent with the ISA formula.

## 7. GPS position Kalman filter (new data-source layer, not inside the calculator)

**Problem:** today's filtering operates on *derived* quantities (distance, speed) via heuristics
in `RideMetricsCalculator` — ad-hoc speed/accel outlier checks, bounce/jump detection, cross-
validation, stationary hysteresis. The raw position itself (lat/lon) is never smoothed; only what's
computed from it is filtered after the fact.

**Key architectural decision — the filter lives in `AndroidRideSensorDataSource`, not inside
`RideMetricsCalculator`.** `RideMetricsCalculator` has 781 lines of tests that feed precise raw
lat/lon sequences and assert exact outcomes (bounce-reversal distance, cold-start behavior, etc.).
Inserting a Kalman filter *inside* the calculator would alter its inputs before those existing
assertions run, requiring most of that suite to be rewritten and re-validated against new filtered
numbers — a large, easy-to-get-subtly-wrong blast radius for a change whose whole point is
improving trustworthiness. Placing the filter one layer up — in the data source, before a
`RideSensorSample` is even constructed — is exactly the pattern already established for
`HeadingSmoother`: a pure, independently-tested unit consumed by the Android-facing class, with
`RideMetricsCalculator` and its full existing test suite left completely untouched. It keeps
receiving lat/lon exactly as before; those values are just better than they used to be.

**Design — new pure class `PositionKalmanFilter` in `core/domain/.../tracking`:**
- Local equirectangular (ENU, meters) projection around the first accepted fix as the coordinate
  origin — avoids the non-uniform-unit problem of running a Kalman filter directly in degrees of
  lat/lon.
- State vector `[x, y, vx, vy]` (meters, meters/sec), constant-velocity motion model between fixes.
- Measurement noise `R` derived per-fix from `Location.accuracy` (variance = accuracy²) rather than
  a fixed constant — a tight fix is trusted more than a loose one.
- Mahalanobis-distance gating on the innovation (residual), threshold at roughly the 99% chi-square
  bound for 2 degrees of freedom — the statistically principled analogue of today's ad-hoc jump/
  bounce detection. On gate failure: predict-only (dead-reckon from the current velocity estimate)
  for that sample; the ungated raw lat/lon is not passed downstream.
- The existing source-level freshness/accuracy gate (fix age ≤5s, accuracy ≤50m) stays exactly as
  it is today, upstream of the Kalman filter.
- `RideMetricsCalculator`'s existing heuristics (bounce detection, cross-validation, stationary
  hysteresis) are **kept as-is**, now operating on the Kalman-filtered signal as a secondary safety
  net rather than being removed. Whether any of them become redundant enough to retire is an
  explicit follow-up decision for a later round, once the filter has real-world ride data behind
  it — not part of this change.

**Testing:** new `PositionKalmanFilterTest` — converges toward the true position under simulated
Gaussian GPS noise, dead-reckons through a synthetic large jump instead of accepting it, produces a
sensible velocity/heading through a simulated turn.

## Rollout

All seven items (including item 5, a no-op plus documented rationale) are independent and can land
as separate commits within one branch/PR, or as separate small PRs — implementer's choice at plan
time. No new permissions. No database schema changes — none of these items require new
`UserSettings` fields or migrations (the Karvonen/HRR zone alternative, which would have needed
one, was explicitly rejected in favor of the no-new-field Tanaka approach).
