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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

            val secondFix =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 50.01
                    longitude = 19.01
                    accuracy = 5.0f
                    speed = 4.0f
                    bearing = 90f
                    time = firstFix.time + 1_000L
                    elapsedRealtimeNanos = firstFix.elapsedRealtimeNanos + 1_000_000_000L
                }
            shadowOf(locationManager).simulateLocation(secondFix)
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(collected).hasSize(2)

            collectorScope.cancel()
        }
}
