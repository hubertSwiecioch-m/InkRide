# 📸 Screenshots

A visual tour of InkRide, captured on an E-Ink-style monochrome display. Screenshots are grouped by feature area.

## Dashboard (Live Ride Tracking)

The dashboard is a horizontal pager with three pages, so a rider can swipe to the metrics that matter most without cluttering a single screen.

| Speed & Core Metrics | Secondary Metrics | Compass |
|---|---|---|
| ![Speed dashboard](screenshots/Screenshot_20260620_135926.png) | ![Secondary metrics](screenshots/Screenshot_20260620_135940.png) | ![Compass](screenshots/Screenshot_20260620_135950.png) |
| Large, high-contrast speed readout plus distance, moving time, average speed, grade %, and live GPS accuracy. `PAUSE`/`STOP` controls are always reachable. | Max speed, elevation gain, calories, altitude (GPS + barometer), and estimated power for the current ride. | Discrete 2°-step compass heading, kept legible and ghosting-free on E-Ink. |

## Ride History & Details

| Ride History List | Ride Summary | Route on Map |
|---|---|---|
| ![Ride history](screenshots/Screenshot_20260620_140016.png) | ![Ride details](screenshots/Screenshot_20260620_140026.png) | ![Route map](screenshots/Screenshot_20260620_140035.png) |
| All recorded rides with date, distance, duration, and average speed at a glance. | Full breakdown per ride: time & date, performance (distance, avg/max speed), and additional stats (elevation gain, calories, average power). | The recorded GPS track plotted on an offline-friendly OpenStreetMap view, opened from the "Show route map" action in ride details. |

![Ride details (scrolled)](screenshots/Screenshot_20260620_140229.png)

Scrolling further down a ride's details reveals the remaining stats and the **Show route map** entry point shown above.

## Settings — Profile

![Profile settings](screenshots/Screenshot_20260620_140058.png)

Rider profile (weight, age) used for calorie/power estimation, plus app language selection (English/Polish).

## Settings — Bike & Sensors

| Bike Tab | Bike Profile Editor | Bluetooth Sensors |
|---|---|---|
| ![Bike settings](screenshots/Screenshot_20260620_140128.png) | ![Bike profile editor](screenshots/Screenshot_20260620_140117.png) | ![Bluetooth sensors](screenshots/Screenshot_20260620_140136.png) |
| Entry points to manage bike profiles and paired Bluetooth sensors. | Create or edit a bike profile: name, weight, and type (Road/MTB/City) — used in ride metric calculations. | Pair BLE heart-rate and cadence sensors using standard GATT profiles, no Google Mobile Services required. |

## Settings — Display

| Units & Metric Toggles | Behavior & Alerts |
|---|---|
| ![Display settings - units](screenshots/Screenshot_20260620_140146.png) | ![Display settings - alerts](screenshots/Screenshot_20260620_140153.png) |
| Choose metric/imperial units and toggle which metrics appear on the dashboard (distance, moving time, avg/max speed, power, compass, ...). | Keep-screen-on while tracking, plus configurable alerts for max speed and heart-rate zone thresholds. |
