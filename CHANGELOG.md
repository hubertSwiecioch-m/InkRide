# Changelog

All notable changes to InkRide are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.1] - 2026-08-31

### Added

- Live heart-rate display now shows a Tanaka `%HRmax` training zone alongside BPM.
- A static "sensor disconnected" indicator appears on the dashboard during an active ride when a paired BLE sensor drops.
- Ride detail now shows an elevation chart alongside the route.
- Raw GPS position is smoothed through a Kalman filter before being used for tracking, reducing jitter in position, speed, and distance.
- Tracking foreground-notification text is now localized.

### Changed

- Heading is now derived from the device's fused rotation-vector sensor instead of raw accelerometer/magnetometer data, improving compass accuracy.
- `PowerEstimator` now adjusts air density for altitude.

### Fixed

- Fixed undo-delete losing a ride's GPS track and laps.
- Fixed a BLE/GPS state race that could drop a live heart-rate/cadence update.
- Fixed distance calculation double-counting a GPS bounce on the outbound leg.
- Heart-rate readings that are physiologically implausible are now rejected instead of accepted.
- Cadence now zeroes out after 3 seconds without a fresh BLE notification, instead of holding a stale value.
- Track-point/lap read and write failures during ride delete/undo now surface instead of failing silently.
- The Kalman filter now only ingests genuinely new GPS fixes, avoiding redundant updates.
