// Robolectric's GnssStatusBuilder is marked deprecated as a whole class in
// 4.16.1 (nudging toward the real android.location.GnssStatus.Builder), but
// that real Builder wasn't added until API 30+ -- it doesn't exist at all
// under this module's sdk=26 Robolectric pin (verified against the actual
// API-26 android-all-instrumented jar: no GnssStatus$Builder class present).
// GnssStatusBuilder is the only viable way to construct a GnssStatus for a
// test running at this API level.
@file:Suppress("DEPRECATION")

package com.speedevand.inkride.tracking.data

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.SensorError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.GnssStatusBuilder

@OptIn(ExperimentalCoroutinesApi::class)
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

            // ShadowLocationManager's simulated delivery re-implements the real
            // LocationManager's minUpdateInterval/minUpdateDistance throttling
            // itself, keyed off Location.elapsedRealtimeNanos (NOT .time) --
            // see LocationTransport.invokeOnLocations in
            // ShadowLocationManager.java. A plain `Location(provider)` leaves
            // elapsedRealtimeNanos at its default of 0, so two fixes built that
            // way are indistinguishable to the shadow's "too fast" guard
            // (0ns - 0ns = 0ms < the 1_000ms minUpdateIntervalMillis requested
            // in AndroidRideSensorDataSource.start()) and the second fix is
            // silently dropped before it ever reaches the registered listener.
            // Setting elapsedRealtimeNanos explicitly, 1 second apart to match
            // the `time` fields below, simulates two real GPS fixes spaced far
            // enough apart to both pass that throttle.
            val firstFix =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 50.0
                    longitude = 19.0
                    accuracy = 5.0f
                    speed = 4.0f
                    bearing = 90f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = 1_000_000_000L
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

            // Small, physically plausible displacement over the 1-second gap
            // (~11m north in 1s, well within PositionKalmanFilter's outlier
            // gate; longitude is left unchanged from the first fix so this is
            // a one-dimensional ~11 m/s nudge, not a multi-hundred-meter jump),
            // so this fix gets blended into the filter's estimate rather than
            // dead-reckoned as an implausible jump -- see RideSampleAssemblerTest's
            // "a new fix time feeds the Kalman filter again" for the same technique.
            val secondFix =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 50.0001
                    longitude = 19.0
                    accuracy = 5.0f
                    speed = 4.0f
                    bearing = 90f
                    time = firstFix.time + 1_000L
                    elapsedRealtimeNanos = firstFix.elapsedRealtimeNanos + 1_000_000_000L
                }
            shadowOf(locationManager).simulateLocation(secondFix)
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(collected).hasSize(2)
            // Proves the second fix was actually blended into the filter
            // (moved from the first sample's latitude), not just that a second
            // sample arrived -- a gated outlier or a broken dedup guard
            // returning the cached first-fix result would both also produce
            // hasSize(2) with latitude ~= 50.0.
            assertThat(collected.last().latitude).isNotNull()
            assertThat(collected.last().latitude!!).isGreaterThan(50.0)
            assertThat(collected.last().latitude!!).isLessThan(50.0001)
            assertThat(collected.last().bearingDegrees).isEqualTo(90f)

            collectorScope.cancel()
        }

    @Test
    fun `satellite count from a GnssStatus update flows into the next emitted sample`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val dataSource = AndroidRideSensorDataSource(context)
            dataSource.start()

            val collected = mutableListOf<RideSensorSample>()
            val collectorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            collectorScope.launch { dataSource.observeSamples().collect { collected.add(it) } }

            // 6 satellites in view, only 4 actually used in the fix -- exercises
            // AndroidRideSensorDataSource's own onSatelliteStatusChanged counting
            // logic (`status.usedInFix(i)`), not just a passthrough of a single
            // pre-summed count.
            val gnssStatus =
                GnssStatusBuilder
                    .create()
                    .addSatellite(satellite(usedInFix = true))
                    .addSatellite(satellite(usedInFix = true))
                    .addSatellite(satellite(usedInFix = true))
                    .addSatellite(satellite(usedInFix = true))
                    .addSatellite(satellite(usedInFix = false))
                    .addSatellite(satellite(usedInFix = false))
                    .build()
            // A GnssStatus update alone doesn't emit a sample -- it only updates
            // lastSatelliteCount, which the *next* location fix picks up.
            shadowOf(locationManager).simulateGnssStatus(gnssStatus)

            val fix =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 50.0
                    longitude = 19.0
                    accuracy = 5.0f
                    speed = 4.0f
                    bearing = 90f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = 1_000_000_000L
                }
            shadowOf(locationManager).simulateLocation(fix)
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(collected).hasSize(1)
            assertThat(collected.last().satelliteCount).isEqualTo(4)

            collectorScope.cancel()
        }

    private fun satellite(usedInFix: Boolean): GnssStatusBuilder.GnssSatelliteInfo =
        GnssStatusBuilder.GnssSatelliteInfo
            .builder()
            .setConstellation(GnssStatus.CONSTELLATION_GPS)
            .setSvid(1)
            .setCn0DbHz(30f)
            .setElevation(45f)
            .setAzimuth(90f)
            .setHasEphemeris(true)
            .setHasAlmanac(true)
            .setUsedInFix(usedInFix)
            .setCarrierFrequencyHz(null)
            .build()
}
