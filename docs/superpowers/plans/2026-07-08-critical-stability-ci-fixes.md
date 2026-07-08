# Critical Stability & CI Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix five small, independent stability/CI gaps found in a project audit: a `versionCode` that never increments, a stale-lint false positive blocking CI adoption, missing `ktlintCheck`/lint gates in CI, an English-only tracking notification, silently-swallowed repository exceptions, and a BLE sensor that never signals disconnection to the rider.

**Architecture:** No new modules or architectural layers. Each fix touches the existing module that already owns the concern (`:app` for versioning, `.github/workflows` for CI, `:feature:tracking:data` for the notification and the lint fix, `:feature:settings:data`/`:feature:history:data`/`:feature:history:presentation` for logging, `:feature:ble:data` + `:core:domain` + `:feature:dashboard:presentation` for the BLE disconnect indicator).

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose, Koin, Room, JUnit 5 + assertk + kotlinx-coroutines-test, ktlint, Android Lint, GitHub Actions.

## Global Constraints

- Privacy-first: no telemetry, no crash-reporting SDK, no network calls added anywhere. Logging (Task 5) is `android.util.Log` only — device-local logcat, nothing leaves the device.
- E-Ink UI constraint: any new UI text is a static label — no animations, no color-only signaling.
- No new Gradle dependencies are needed for any task in this plan.
- Existing tests must keep passing after every task; `ktlintCheck` must stay clean throughout (it already is, confirmed via a local run before this plan was written).
- Android modules (everything under `:feature:*` and `:core:*` except `:core:domain`) use the variant-aware test task: `./gradlew :module:path:testDebugUnitTest`. `:core:domain` is pure Kotlin and uses `./gradlew :core:domain:test`.

---

### Task 1: `versionCode` auto-increment from the CI build number

**Files:**
- Modify: `app/build.gradle.kts:37`
- Modify: `.github/workflows/release.yml` (the `Build signed release APK` step)

**Interfaces:** None (build configuration only).

- [ ] **Step 1: Confirm the current (broken) behavior**

Run: `grep -n "versionCode" app/build.gradle.kts`
Expected output: `        versionCode = 1`

- [ ] **Step 2: Make `versionCode` readable from a Gradle project property**

In `app/build.gradle.kts`, replace line 37:

```kotlin
        versionCode = 1
```

with:

```kotlin
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
```

- [ ] **Step 3: Pass the CI build number as `versionCode` on release builds**

In `.github/workflows/release.yml`, find this step:

```yaml
      - name: Build signed release APK
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew assembleRelease --no-daemon
```

Replace the `run:` line with:

```yaml
        run: ./gradlew assembleRelease --no-daemon -PversionCode=${{ github.run_number }}
```

- [ ] **Step 4: Verify the default (no property) build still works**

Run: `./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. This exercises the `?: 1` fallback path used by every local/dev build.

- [ ] **Step 5: Verify the property actually reaches the merged manifest**

Run:
```bash
./gradlew :app:assembleDebug --no-daemon -PversionCode=42
$ANDROID_HOME/build-tools/*/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep versionCode
```
Expected: output contains `versionCode='42'`.

If `aapt` isn't on `PATH`, instead run:
```bash
grep -o "versionCode=\"[0-9]*\"" app/build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml
```
Expected: `versionCode="42"`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts .github/workflows/release.yml
git commit -m "Auto-increment versionCode from the CI build number"
```

---

### Task 2: Fix the pre-existing Android Lint false positive on `TrackingService.vibrateFor`

This must land before Task 3 (CI lint gate) — otherwise the very first CI run with lint enabled goes red on a pre-existing issue unrelated to any new change.

**Files:**
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/service/TrackingService.kt:114-123`

**Interfaces:** None (annotation-only change, no signature changes).

- [ ] **Step 1: Reproduce the failure**

Run: `./gradlew :feature:tracking:data:lintDebug --no-daemon`
Expected: `BUILD FAILED` with:
```
feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/service/TrackingService.kt:122: Error: Missing permissions required by Vibrator.vibrate: android.permission.VIBRATE [MissingPermission]
        vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, -1))
```

- [ ] **Step 2: Suppress with a justifying comment**

In `TrackingService.kt`, replace:

```kotlin
    /**
     * Distinct vibration patterns per alert so the rider can tell them apart
     * without looking: a single long buzz for over-speed, two short for HR-high,
     * one short for HR-low, three short for off-route.
     */
    private fun vibrateFor(alert: RideAlert) {
        val pattern =
            when (alert) {
                is RideAlert.OverSpeed -> longArrayOf(0, 500)
                is RideAlert.HeartRateHigh -> longArrayOf(0, 200, 150, 200)
                is RideAlert.HeartRateLow -> longArrayOf(0, 200)
                is RideAlert.OffRoute -> longArrayOf(0, 150, 100, 150, 100, 150)
            }
        vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
```

with:

```kotlin
    /**
     * Distinct vibration patterns per alert so the rider can tell them apart
     * without looking: a single long buzz for over-speed, two short for HR-high,
     * one short for HR-low, three short for off-route.
     */
    // VIBRATE is a normal permission declared in app/src/main/AndroidManifest.xml.
    // Lint running at this library module's level can't see the merged app
    // manifest, so it flags a false positive here.
    @Suppress("MissingPermission")
    private fun vibrateFor(alert: RideAlert) {
        val pattern =
            when (alert) {
                is RideAlert.OverSpeed -> longArrayOf(0, 500)
                is RideAlert.HeartRateHigh -> longArrayOf(0, 200, 150, 200)
                is RideAlert.HeartRateLow -> longArrayOf(0, 200)
                is RideAlert.OffRoute -> longArrayOf(0, 150, 100, 150, 100, 150)
            }
        vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
```

- [ ] **Step 3: Verify lint passes**

Run: `./gradlew :feature:tracking:data:lintDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify the full lint suite across all modules also passes**

Run: `./gradlew lintDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. (This is the same command Task 3 will add to CI — confirming it here means Task 3 can't fail on account of this issue.)

- [ ] **Step 5: Commit**

```bash
git add feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/service/TrackingService.kt
git commit -m "Fix pre-existing Android Lint false positive on TrackingService vibration"
```

---

### Task 3: Add `ktlintCheck` and Android Lint to CI

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:** None.

- [ ] **Step 1: Add the two steps to the CI workflow**

In `.github/workflows/ci.yml`, replace:

```yaml
      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --no-daemon

      - name: Assemble debug APK
        run: ./gradlew assembleDebug --no-daemon
```

with:

```yaml
      - name: Check Kotlin formatting (ktlint)
        run: ./gradlew ktlintCheck --no-daemon

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --no-daemon

      - name: Run Android Lint
        run: ./gradlew lintDebug --no-daemon

      - name: Assemble debug APK
        run: ./gradlew assembleDebug --no-daemon
```

- [ ] **Step 2: Verify both new commands pass locally**

Run: `./gradlew ktlintCheck --no-daemon`
Expected: `BUILD SUCCESSFUL` (no output — already confirmed clean before this plan was written).

Run: `./gradlew lintDebug --no-daemon`
Expected: `BUILD SUCCESSFUL` (confirmed by Task 2, Step 4 — re-run here only if Task 2 landed as a separate PR/branch).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Add ktlintCheck and Android Lint gates to CI"
```

- [ ] **Step 4: Push and confirm CI goes green**

This step can only be verified once the branch/PR is pushed — note in the PR description that the new `ktlintCheck` and `lintDebug` steps are expected to appear and pass in the Actions run.

---

### Task 4: Localize the `TrackingService` foreground-notification text

**Files:**
- Create: `feature/tracking/data/src/main/res/values/strings.xml`
- Create: `feature/tracking/data/src/main/res/values-pl/strings.xml`
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/service/TrackingService.kt:78-85`

**Interfaces:** None (internal to the service; no public signature changes).

- [ ] **Step 1: Add the English string resources**

Create `feature/tracking/data/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="notification_tracking_title">InkRide</string>
    <string name="notification_tracking_text">Tracking ride…</string>
</resources>
```

- [ ] **Step 2: Add the Polish string resources**

Create `feature/tracking/data/src/main/res/values-pl/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="notification_tracking_title">InkRide</string>
    <string name="notification_tracking_text">Nagrywanie przejazdu…</string>
</resources>
```

- [ ] **Step 3: Use the string resources in `TrackingService`**

In `TrackingService.kt`, replace:

```kotlin
        val notification =
            NotificationCompat
                .Builder(this, channelId)
                .setContentTitle("InkRide")
                .setContentText("Tracking ride…")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
```

with:

```kotlin
        val notification =
            NotificationCompat
                .Builder(this, channelId)
                .setContentTitle(getString(R.string.notification_tracking_title))
                .setContentText(getString(R.string.notification_tracking_text))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
```

Add the import (alphabetically, after the `android.os.VibratorManager` import and before `androidx.core.app.NotificationCompat`):

```kotlin
import com.speedevand.inkride.tracking.data.R
```

- [ ] **Step 4: Verify the module builds and resources resolve**

Run: `./gradlew :feature:tracking:data:assembleDebug :feature:tracking:data:ktlintCheck --no-daemon`
Expected: `BUILD SUCCESSFUL` (confirms the generated `R` class contains the new string resources, the reference compiles, and the new import/formatting is ktlint-clean).

- [ ] **Step 5: Manual verification (no automated test — this is a display-text change)**

Install the debug APK, switch the device/emulator system language to Polish, start a ride, pull down the notification shade, and confirm the notification reads "InkRide" / "Nagrywanie przejazdu…".

- [ ] **Step 6: Commit**

```bash
git add feature/tracking/data/src/main/res feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/service/TrackingService.kt
git commit -m "Localize the tracking foreground-notification text"
```

---

### Task 5: Add local error logging to repositories and the GPX exporter

**Context:** `RoomUserSettingsRepositoryTest` and `RoomRideHistoryRepositoryTest` already have tests that deliberately trigger the `catch` blocks this task modifies (e.g. `save DISK_FULL returns Error`). Android Gradle Plugin's unit-test stub `android.jar` makes every unmocked Android method throw `RuntimeException` by default (`testOptions.unitTests.isReturnDefaultValues` defaults to `false`). Adding a real `android.util.Log.e(...)` call inside those catch blocks would make the stubbed `Log.e` throw and break those tests — unless this default is flipped first. Step 1 does that, once, for every Android library/feature module (the convention plugin all of them apply).

**Files:**
- Modify: `build-logic/convention/src/main/kotlin/com/speedevand/inkride/convention/AndroidLibraryConventionPlugin.kt`
- Modify: `feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomBikeProfileRepository.kt`
- Modify: `feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomUserSettingsRepository.kt`
- Modify: `feature/history/data/src/main/java/com/speedevand/inkride/history/data/RoomRideHistoryRepository.kt`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/GpxExporter.kt`

**Interfaces:** None (logging is a side effect added inside existing `catch` blocks; no signatures change).

- [ ] **Step 1: Let stubbed Android methods return default values in unit tests**

In `AndroidLibraryConventionPlugin.kt`, replace:

```kotlin
            extensions.configure<LibraryExtension> {
                compileSdk = 36
                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }
```

with:

```kotlin
            extensions.configure<LibraryExtension> {
                compileSdk = 36
                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
                // Unmocked Android framework calls (e.g. android.util.Log) would
                // otherwise throw in plain JVM unit tests. Returning defaults lets
                // production code call them without every test needing a mock.
                testOptions {
                    unitTests {
                        isReturnDefaultValues = true
                    }
                }
            }
```

- [ ] **Step 2: Verify the flag alone doesn't change any existing test result**

Run: `./gradlew :feature:settings:data:testDebugUnitTest :feature:history:data:testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`, same tests passing as before this step.

- [ ] **Step 3: Log in `RoomBikeProfileRepository`**

Replace the full file `feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomBikeProfileRepository.kt` with:

```kotlin
package com.speedevand.inkride.settings.data

import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.speedevand.inkride.core.database.BikeProfileDao
import com.speedevand.inkride.core.database.BikeProfileEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.BikeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "RoomBikeProfileRepository"

class RoomBikeProfileRepository(
    private val dao: BikeProfileDao,
) : BikeProfileRepository {
    override fun observeProfiles(): Flow<List<BikeProfile>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(profile: BikeProfile): Result<Long, DataError.Local> =
        try {
            Result.Success(dao.upsert(profile.toEntity()))
        } catch (e: SQLiteFullException) {
            Log.e(TAG, "upsert failed: disk full", e)
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Log.e(TAG, "upsert failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun delete(id: Long): EmptyResult<DataError.Local> =
        try {
            dao.deleteById(id)
            Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "delete failed for id=$id", e)
            Result.Error(DataError.Local.UNKNOWN)
        }
}

private fun BikeProfileEntity.toDomain() =
    BikeProfile(
        id = id,
        name = name,
        weightKg = weightKg,
        type =
            try {
                BikeType.valueOf(type)
            } catch (e: Exception) {
                BikeType.ROAD
            },
    )

private fun BikeProfile.toEntity() =
    BikeProfileEntity(
        id = id,
        name = name,
        weightKg = weightKg,
        type = type.name,
    )
```

- [ ] **Step 4: Log in `RoomUserSettingsRepository`**

In `RoomUserSettingsRepository.kt`, add the import `import android.util.Log` (alphabetically first, before `android.database.sqlite.SQLiteFullException`... note `android.database.sqlite.SQLiteFullException` sorts before `android.util.Log` alphabetically, so insert after it) and a `private const val TAG = "RoomUserSettingsRepository"` above the class. Replace the `save` function:

```kotlin
    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> =
        try {
            dao.upsert(
                UserSettingsEntity(
                    weightKg = settings.weightKg,
                    age = settings.age,
                    bikeWeightKg = settings.bikeWeightKg,
                    bikeType = settings.bikeType.name,
                    languageCode = settings.languageCode,
                    units = settings.units.name,
                    showDistance = settings.showDistance,
                    showMovingTime = settings.showMovingTime,
                    showAverageSpeed = settings.showAverageSpeed,
                    showMaxSpeed = settings.showMaxSpeed,
                    showElevationGain = settings.showElevationGain,
                    showCalories = settings.showCalories,
                    showAltitude = settings.showAltitude,
                    showGrade = settings.showGrade,
                    showCompass = settings.showCompass,
                    showPower = settings.showPower,
                    keepScreenOn = settings.keepScreenOn,
                    pairedHrmAddress = settings.pairedHrmAddress,
                    pairedCadenceAddress = settings.pairedCadenceAddress,
                    maxSpeedAlertKmh = settings.alerts.maxSpeedKmh,
                    hrZoneMinBpm = settings.alerts.hrZoneMinBpm,
                    hrZoneMaxBpm = settings.alerts.hrZoneMaxBpm,
                    activeBikeProfileId = settings.activeBikeProfileId,
                ),
            )
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
```

with (only the two `catch` bodies change):

```kotlin
    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> =
        try {
            dao.upsert(
                UserSettingsEntity(
                    weightKg = settings.weightKg,
                    age = settings.age,
                    bikeWeightKg = settings.bikeWeightKg,
                    bikeType = settings.bikeType.name,
                    languageCode = settings.languageCode,
                    units = settings.units.name,
                    showDistance = settings.showDistance,
                    showMovingTime = settings.showMovingTime,
                    showAverageSpeed = settings.showAverageSpeed,
                    showMaxSpeed = settings.showMaxSpeed,
                    showElevationGain = settings.showElevationGain,
                    showCalories = settings.showCalories,
                    showAltitude = settings.showAltitude,
                    showGrade = settings.showGrade,
                    showCompass = settings.showCompass,
                    showPower = settings.showPower,
                    keepScreenOn = settings.keepScreenOn,
                    pairedHrmAddress = settings.pairedHrmAddress,
                    pairedCadenceAddress = settings.pairedCadenceAddress,
                    maxSpeedAlertKmh = settings.alerts.maxSpeedKmh,
                    hrZoneMinBpm = settings.alerts.hrZoneMinBpm,
                    hrZoneMaxBpm = settings.alerts.hrZoneMaxBpm,
                    activeBikeProfileId = settings.activeBikeProfileId,
                ),
            )
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Log.e(TAG, "save failed: disk full", e)
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }
```

- [ ] **Step 5: Log in `RoomRideHistoryRepository`**

Replace the full file `feature/history/data/src/main/java/com/speedevand/inkride/history/data/RoomRideHistoryRepository.kt` with:

```kotlin
package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.speedevand.inkride.core.database.RideHistoryDao
import com.speedevand.inkride.core.database.RideHistoryEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.BikeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "RoomRideHistoryRepository"

class RoomRideHistoryRepository(
    private val dao: RideHistoryDao,
) : RideHistoryRepository {
    override fun observeAll(): Flow<List<RideRecord>> =
        dao.observeAll().map { list ->
            list.map { it.toRideRecord() }
        }

    override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> {
        val entity = dao.getById(id)
        return if (entity != null) {
            Result.Success(entity.toRideRecord())
        } else {
            Result.Error(DataError.Local.NOT_FOUND)
        }
    }

    override suspend fun save(ride: RideRecord): Result<Long, DataError.Local> =
        try {
            Result.Success(dao.insert(ride.toEntity()))
        } catch (e: SQLiteFullException) {
            Log.e(TAG, "save failed: disk full", e)
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> =
        try {
            dao.deleteById(id)
            Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteById failed for id=$id", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> =
        try {
            dao.deleteAll()
            Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteAll failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }
}

private fun RideHistoryEntity.toRideRecord() =
    RideRecord(
        id = id,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        elapsedTimeSeconds = elapsedTimeSeconds,
        averageSpeedKmh = averageSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        elevationGainM = elevationGainM,
        caloriesKcal = caloriesKcal,
        averagePowerWatts = averagePowerWatts,
        bikeWeightKg = bikeWeightKg,
        bikeType =
            try {
                BikeType.valueOf(bikeType)
            } catch (e: Exception) {
                BikeType.ROAD
            },
    )

private fun RideRecord.toEntity() =
    RideHistoryEntity(
        id = id,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        elapsedTimeSeconds = elapsedTimeSeconds,
        averageSpeedKmh = averageSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        elevationGainM = elevationGainM,
        caloriesKcal = caloriesKcal,
        averagePowerWatts = averagePowerWatts,
        bikeWeightKg = bikeWeightKg,
        bikeType = bikeType.name,
    )
```

- [ ] **Step 6: Log in `AndroidGpxExporter`**

In `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/GpxExporter.kt`, add the import `import android.util.Log` (after `import android.net.Uri`) and replace:

```kotlin
            try {
                val gpx = GpxBuilder.build(ride, points)
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val dir = File(baseDir, GPX_DIR).apply { mkdirs() }
                val file = File(dir, "inkride_${fileTimestamp(ride.startTimestamp)}.gpx")
                file.writeText(gpx)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                Result.Success(uri)
            } catch (e: Exception) {
                Result.Error(GpxExportError.FAILED)
            }
        }

    private fun fileTimestamp(ms: Long): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))

    private companion object {
        const val GPX_DIR = "gpx"
    }
```

with:

```kotlin
            try {
                val gpx = GpxBuilder.build(ride, points)
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val dir = File(baseDir, GPX_DIR).apply { mkdirs() }
                val file = File(dir, "inkride_${fileTimestamp(ride.startTimestamp)}.gpx")
                file.writeText(gpx)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                Result.Success(uri)
            } catch (e: Exception) {
                Log.e(TAG, "GPX export failed for rideId=$rideId", e)
                Result.Error(GpxExportError.FAILED)
            }
        }

    private fun fileTimestamp(ms: Long): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))

    private companion object {
        const val TAG = "AndroidGpxExporter"
        const val GPX_DIR = "gpx"
    }
```

- [ ] **Step 7: Run the full affected test suite**

Run: `./gradlew :feature:settings:data:testDebugUnitTest :feature:history:data:testDebugUnitTest :feature:history:presentation:testDebugUnitTest :feature:settings:data:ktlintCheck :feature:history:data:ktlintCheck :feature:history:presentation:ktlintCheck --no-daemon`
Expected: `BUILD SUCCESSFUL`, in particular `RoomUserSettingsRepositoryTest.save DISK_FULL returns Error`, `RoomUserSettingsRepositoryTest.save unknown exception returns UNKNOWN error`, and the equivalent `RoomRideHistoryRepositoryTest` cases still pass (they now execute a real `Log.e` call that returns a default value instead of throwing), and ktlint stays clean.

- [ ] **Step 8: Commit**

```bash
git add build-logic/convention/src/main/kotlin/com/speedevand/inkride/convention/AndroidLibraryConventionPlugin.kt \
        feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomBikeProfileRepository.kt \
        feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomUserSettingsRepository.kt \
        feature/history/data/src/main/java/com/speedevand/inkride/history/data/RoomRideHistoryRepository.kt \
        feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/GpxExporter.kt
git commit -m "Log swallowed repository/export exceptions locally via android.util.Log"
```

---

### Task 6: Surface BLE sensor connection state through the domain layer

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSample.kt`
- Modify: `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:**
- Produces: `BleSample.connected: Boolean` (default `false`) — true while at least one paired BLE device has a live GATT connection.
- Produces: `TrackingState.bleSensorConnected: Boolean` (default `false`) — mirrors the latest `BleSample.connected` while a ride is not `IDLE`. Consumed by Task 7.

- [ ] **Step 1: Add the `connected` field to `BleSample`**

Replace `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSample.kt`:

```kotlin
package com.speedevand.inkride.core.domain.ble

/**
 * Latest values read from paired BLE sensors. Each field is null until a sensor
 * of that kind is connected and has reported a value. [wheelRevolutions] is the
 * cumulative count from a CSC sensor, exposed for completeness; [cadenceRpm] is
 * the derived crank cadence most riders care about. [connected] is true while at
 * least one paired device has a live GATT connection; it flips to false (with
 * the readings cleared) as soon as a sensor drops, so stale values never linger.
 */
data class BleSample(
    val timestampMs: Long,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val wheelRevolutions: Long? = null,
    val connected: Boolean = false,
)
```

- [ ] **Step 2: Write the failing `RideTracker` test**

In `RideTrackerTest.kt`, add the import `import assertk.assertions.isFalse` (alongside the existing `assertk.assertions.*` imports, keeping alphabetical order — it goes after `isEqualTo` and before `isGreaterThan`... actually alphabetically `isFalse` comes before `isGreaterThan` and after `isEqualTo`; insert accordingly). Then add this test after `a BLE sample updates live metrics without a GPS fix`:

```kotlin
    @Test
    fun `a BLE disconnect clears the connected flag`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88, connected = true))
            assertThat(tracker.state.value.bleSensorConnected).isTrue()

            ble.samples.emit(BleSample(timestampMs = 1000L, connected = false))
            assertThat(tracker.state.value.bleSensorConnected).isFalse()
        }
```

Note: `isTrue` is already imported in this file; only `isFalse` needs adding.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: FAIL — `bleSensorConnected` doesn't exist on `TrackingState` yet (compile error).

- [ ] **Step 4: Add `bleSensorConnected` to `TrackingState`**

In `RideTracker.kt`, replace:

```kotlin
data class TrackingState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val metrics: RideMetrics = RideMetrics(),
    val laps: List<LapRecord> = emptyList(),
    val activeGoal: RideGoal? = null,
    // Loaded GPX route the rider is following, and their live progress along it.
    // Both null when no route is loaded.
    val activeRoute: PlannedRoute? = null,
    val routeProgress: RouteProgress? = null,
)
```

with:

```kotlin
data class TrackingState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val metrics: RideMetrics = RideMetrics(),
    val laps: List<LapRecord> = emptyList(),
    val activeGoal: RideGoal? = null,
    // Loaded GPX route the rider is following, and their live progress along it.
    // Both null when no route is loaded.
    val activeRoute: PlannedRoute? = null,
    val routeProgress: RouteProgress? = null,
    // Mirrors the latest BleSample.connected while a ride is active. False both
    // when nothing is paired and when a paired sensor has dropped — Task 7's UI
    // combines this with the paired-address settings to tell the two apart.
    val bleSensorConnected: Boolean = false,
)
```

- [ ] **Step 5: Fold `connected` into the published state in the BLE collector**

In `RideTracker.kt`, replace:

```kotlin
                val bleJob =
                    launch {
                        bleSensorDataSource.observeSamples().collect { ble ->
                            latestBle = ble
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
                            latestBle = ble
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

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: PASS, all `RideTrackerTest` cases green including the new one.

- [ ] **Step 7: Track live GATT connections in `AndroidBleSensorDataSource`**

In `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt`, add a new field after `connectedAddresses`:

```kotlin
    // Desired addresses currently requested, so connect() can be idempotent.
    @Volatile
    private var connectedAddresses: Set<String> = emptySet()

    // Addresses with an actual live GATT connection right now (a subset of
    // connectedAddresses — a desired address may still be reconnecting).
    // Touched only from the GATT callback thread, which Android guarantees is
    // serial, so no extra synchronization is needed beyond the ConcurrentHashMap
    // key set already used for gatts/cadenceTrackers in this class.
    private val liveAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
```

Replace `onConnectionStateChange`:

```kotlin
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                }
            }
```

with:

```kotlin
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                val address = gatt.device?.address
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        address?.let { liveAddresses.add(it) }
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        address?.let { liveAddresses.remove(it) }
                        // Don't let a stale reading from the now-gone sensor
                        // linger — the rider should see it's disconnected, not
                        // its last value forever.
                        latestHeartRate = null
                        latestCadence = null
                        latestWheelRevolutions = null
                        emit()
                    }
                }
            }
```

Replace `emit()`:

```kotlin
    private fun emit() {
        samples.value =
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = latestHeartRate,
                cadenceRpm = latestCadence,
                wheelRevolutions = latestWheelRevolutions,
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
            )
    }
```

Replace `disconnect()`:

```kotlin
    override fun disconnect() {
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
        cadenceTrackers.clear()
        pendingNotifications.clear()
        connectedAddresses = emptySet()
        latestHeartRate = null
        latestCadence = null
        latestWheelRevolutions = null
        emit()
    }
```

with:

```kotlin
    override fun disconnect() {
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
        cadenceTrackers.clear()
        pendingNotifications.clear()
        liveAddresses.clear()
        connectedAddresses = emptySet()
        latestHeartRate = null
        latestCadence = null
        latestWheelRevolutions = null
        emit()
    }
```

`ConcurrentHashMap` is already imported in this file (`java.util.concurrent.ConcurrentHashMap`), so no new import is needed.

`AndroidBleSensorDataSource` depends on the real `android.bluetooth.BluetoothGatt`/`BluetoothProfile` framework classes (final, not mockable without Robolectric, which this project doesn't use), so it has no unit test today (`BleGattTest.kt` only tests the free-standing parsing functions in `BleGatt.kt`) and this plan doesn't add one — the behavior is covered at the `RideTracker` level via the `BleSensorDataSource` interface, per Step 2-6 above.

- [ ] **Step 8: Verify the BLE module still builds**

Run: `./gradlew :feature:ble:data:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Run the full `core:domain` and `feature:ble:data` test suites**

Run: `./gradlew :core:domain:test :feature:ble:data:testDebugUnitTest :core:domain:ktlintCheck :feature:ble:data:ktlintCheck --no-daemon`
Expected: `BUILD SUCCESSFUL`, all tests green (including the untouched `BleGattTest`), ktlint clean.

- [ ] **Step 10: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSample.kt \
        core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt \
        feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt
git commit -m "Surface BLE sensor connection state through RideTracker"
```

---

### Task 7: Show a "sensor disconnected" indicator on the dashboard

**Files:**
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardContract.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardViewModel.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/InfoBar.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt:225`
- Modify: `feature/dashboard/presentation/src/main/res/values/strings.xml`
- Modify: `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`

**Interfaces:**
- Consumes: `TrackingState.bleSensorConnected: Boolean` (from Task 6), `UserSettings.pairedHrmAddress: String?`, `UserSettings.pairedCadenceAddress: String?` (both already exist).
- Produces: `DashboardState.bleSensorConnected: Boolean`, consumed by `DashboardScreen`.

- [ ] **Step 1: Add `bleSensorConnected` to `DashboardState`**

In `DashboardContract.kt`, replace:

```kotlin
@Stable
data class DashboardState(
    val rideMetrics: RideMetricsUi = RideMetricsUi(),
    val status: TrackingStatus = TrackingStatus.IDLE,
    val userSettings: UserSettings = UserSettings(weightKg = 75, age = 30),
    val lastLap: LapSummaryUi? = null,
    val goal: GoalProgressUi? = null,
    val route: RouteProgressUi? = null,
)
```

with:

```kotlin
@Stable
data class DashboardState(
    val rideMetrics: RideMetricsUi = RideMetricsUi(),
    val status: TrackingStatus = TrackingStatus.IDLE,
    val userSettings: UserSettings = UserSettings(weightKg = 75, age = 30),
    val lastLap: LapSummaryUi? = null,
    val goal: GoalProgressUi? = null,
    val route: RouteProgressUi? = null,
    val bleSensorConnected: Boolean = false,
)
```

- [ ] **Step 2: Populate it in `DashboardViewModel`**

In `DashboardViewModel.kt`, replace:

```kotlin
                DashboardState(
                    rideMetrics = tracking.metrics.toRideMetricsUi(settings.units),
                    status = tracking.status,
                    userSettings = settings,
                    lastLap = tracking.laps.lastOrNull()?.toSummaryUi(settings.units),
                    goal = tracking.activeGoal?.let { goalProgressUi(it, tracking.metrics, settings.units) },
                    route =
                        tracking.activeRoute?.let {
                            routeProgressUi(it, tracking.routeProgress, settings.units)
                        },
                )
```

with:

```kotlin
                DashboardState(
                    rideMetrics = tracking.metrics.toRideMetricsUi(settings.units),
                    status = tracking.status,
                    userSettings = settings,
                    lastLap = tracking.laps.lastOrNull()?.toSummaryUi(settings.units),
                    goal = tracking.activeGoal?.let { goalProgressUi(it, tracking.metrics, settings.units) },
                    route =
                        tracking.activeRoute?.let {
                            routeProgressUi(it, tracking.routeProgress, settings.units)
                        },
                    bleSensorConnected = tracking.bleSensorConnected,
                )
```

- [ ] **Step 3: Add the string resources**

In `feature/dashboard/presentation/src/main/res/values/strings.xml`, in the `<!-- Info bar -->` section, replace:

```xml
    <!-- Info bar -->
    <string name="dashboard_gps_accuracy">GPS accuracy: %1$s %2$s</string>
    <string name="dashboard_heart_rate">%1$s bpm</string>
    <string name="dashboard_cadence">%1$s rpm</string>
    <string name="dashboard_weather">Baro %1$s</string>
    <string name="dashboard_weather_rising">↑ rising</string>
    <string name="dashboard_weather_falling">↓ falling</string>
    <string name="dashboard_weather_stable">→ stable</string>
```

with:

```xml
    <!-- Info bar -->
    <string name="dashboard_gps_accuracy">GPS accuracy: %1$s %2$s</string>
    <string name="dashboard_heart_rate">%1$s bpm</string>
    <string name="dashboard_cadence">%1$s rpm</string>
    <string name="dashboard_sensor_disconnected">Sensor disconnected</string>
    <string name="dashboard_weather">Baro %1$s</string>
    <string name="dashboard_weather_rising">↑ rising</string>
    <string name="dashboard_weather_falling">↓ falling</string>
    <string name="dashboard_weather_stable">→ stable</string>
```

In `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`, replace the equivalent block:

```xml
    <!-- Info bar -->
    <string name="dashboard_gps_accuracy">Dokładność GPS: %1$s %2$s</string>
    <string name="dashboard_heart_rate">%1$s bpm</string>
    <string name="dashboard_cadence">%1$s obr/min</string>
    <string name="dashboard_weather">Baro %1$s</string>
    <string name="dashboard_weather_rising">↑ rośnie</string>
    <string name="dashboard_weather_falling">↓ spada</string>
    <string name="dashboard_weather_stable">→ stabilne</string>
```

with:

```xml
    <!-- Info bar -->
    <string name="dashboard_gps_accuracy">Dokładność GPS: %1$s %2$s</string>
    <string name="dashboard_heart_rate">%1$s bpm</string>
    <string name="dashboard_cadence">%1$s obr/min</string>
    <string name="dashboard_sensor_disconnected">Czujnik rozłączony</string>
    <string name="dashboard_weather">Baro %1$s</string>
    <string name="dashboard_weather_rising">↑ rośnie</string>
    <string name="dashboard_weather_falling">↓ spada</string>
    <string name="dashboard_weather_stable">→ stabilne</string>
```

- [ ] **Step 4: Add the indicator to `InfoBar`**

Replace `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/InfoBar.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.domain.tracking.WeatherTrend
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi

@Composable
fun InfoBar(
    metrics: RideMetricsUi,
    sensorPaired: Boolean,
    sensorConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(
            text =
                stringResource(
                    R.string.dashboard_gps_accuracy,
                    metrics.gpsAccuracyM,
                    metrics.altitudeUnit,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        metrics.heartRateBpm?.let {
            TextMMD(
                text = stringResource(R.string.dashboard_heart_rate, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        metrics.cadenceRpm?.let {
            TextMMD(
                text = stringResource(R.string.dashboard_cadence, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (sensorPaired && !sensorConnected) {
            TextMMD(
                text = stringResource(R.string.dashboard_sensor_disconnected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        metrics.weatherTrend.labelRes()?.let { labelRes ->
            TextMMD(
                text = stringResource(R.string.dashboard_weather, stringResource(labelRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// Static, E-Ink-friendly arrow glyph per trend; null hides the readout until
// enough barometer history accrues.
private fun WeatherTrend.labelRes(): Int? =
    when (this) {
        WeatherTrend.RISING -> R.string.dashboard_weather_rising
        WeatherTrend.FALLING -> R.string.dashboard_weather_falling
        WeatherTrend.STABLE -> R.string.dashboard_weather_stable
        WeatherTrend.UNKNOWN -> null
    }
```

- [ ] **Step 5: Update the `InfoBar` call site**

In `DashboardScreen.kt`, replace:

```kotlin
                InfoBar(metrics = state.rideMetrics)
```

with:

```kotlin
                InfoBar(
                    metrics = state.rideMetrics,
                    sensorPaired = state.userSettings.pairedHrmAddress != null || state.userSettings.pairedCadenceAddress != null,
                    sensorConnected = state.bleSensorConnected,
                )
```

- [ ] **Step 6: Build and run the module's tests**

Run: `./gradlew :feature:dashboard:presentation:assembleDebug :feature:dashboard:presentation:testDebugUnitTest :feature:dashboard:presentation:ktlintCheck --no-daemon`
Expected: `BUILD SUCCESSFUL`. (`InfoBar` has no dedicated test; `RideMetricsUiTest` and `SensorErrorToUiTextTest` are unaffected by this change and should stay green — this step confirms that, and that the new code is ktlint-clean.)

- [ ] **Step 7: Manual verification**

Run the app (see the project's `run` workflow if available, or `./gradlew :app:installDebug` on a connected device/emulator), pair a BLE HR or cadence sensor in Settings, start a ride, then turn the sensor off (or walk out of range). Confirm:
- The HR/cadence reading in the info bar disappears (goes back to not being shown).
- A static "Sensor disconnected" label appears in its place.
- No animation, no flicker — a single static text change, consistent with the E-Ink constraint.

- [ ] **Step 8: Commit**

```bash
git add feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardContract.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardViewModel.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/InfoBar.kt \
        feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt \
        feature/dashboard/presentation/src/main/res/values/strings.xml \
        feature/dashboard/presentation/src/main/res/values-pl/strings.xml
git commit -m "Show a static 'sensor disconnected' indicator on the dashboard"
```

---

## Final verification (after all 7 tasks)

- [ ] Run the full build and test suite: `./gradlew ktlintCheck testDebugUnitTest :core:domain:test lintDebug assembleDebug --no-daemon`
  Expected: `BUILD SUCCESSFUL` end to end.
