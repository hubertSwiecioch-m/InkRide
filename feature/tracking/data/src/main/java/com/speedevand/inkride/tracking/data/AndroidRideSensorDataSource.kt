package com.speedevand.inkride.tracking.data

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.SensorError
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AndroidRideSensorDataSource(
    private val context: Context
) : RideSensorDataSource {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // All sensor/location callbacks are delivered on the main looper, so start()
    // can be called from any thread (e.g. a background coroutine in the process-
    // scoped RideTracker) without the "Looper.prepare()" crash that the no-Looper
    // requestLocationUpdates / registerListener overloads would otherwise cause.
    private val callbackHandler = Handler(Looper.getMainLooper())

    // Buffer 60 samples (~60 seconds at 1 Hz GPS) to survive brief backpressure
    // without silently dropping samples. Uses DROP_OLDEST so the most recent
    // data is always available.
    private val _samples = MutableSharedFlow<RideSensorSample>(
        extraBufferCapacity = 60,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override fun observeSamples(): Flow<RideSensorSample> = _samples.asSharedFlow()

    private var locationListener: LocationListener? = null
    private var pressureListener: SensorEventListener? = null
    private var orientationListener: SensorEventListener? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    private var lastLocation: Location? = null
    private var lastPressureHpa: Float? = null
    // True-north heading (magnetic reading + declination), circular-EMA smoothed.
    private var lastHeading: Float? = null
    // Raw smoothed magnetic heading state for the circular EMA filter.
    private var smoothedHeadingDeg: Float? = null
    // Last heading actually emitted — used to throttle emissions to ~2° steps,
    // matching the E-Ink compass's discrete rendering and cutting sample churn.
    private var lastEmittedHeadingDeg: Float? = null
    // Magnetic declination (degrees to add to a magnetic heading to get true
    // north), refreshed from the current location. 0 until the first fix.
    private var magneticDeclinationDeg: Float = 0f
    // True while the magnetometer reports UNRELIABLE/LOW calibration accuracy.
    // While set, magnetometer-derived heading is suppressed (GPS course-over-
    // ground is still used when moving). Unknown accuracy is treated as usable
    // so devices that never fire onAccuracyChanged still get a compass.
    private var isMagnetometerUnreliable: Boolean = false

    // Heading is only emitted when it changes by at least this many degrees.
    private val headingEmitThresholdDeg: Float = 2.0f
    // EMA smoothing factor for the magnetometer heading (higher = more responsive,
    // lower = smoother). 0.2 tames magnetometer jitter without feeling laggy.
    private val headingSmoothingAlpha: Float = 0.2f
    // Above this speed, GPS course-over-ground is more trustworthy than the
    // magnetometer (which is easily disturbed by the bike frame and phone).
    private val gpsBearingMinSpeedMps: Float = 2.0f

    // Satellite count from GnssStatus — used for GPS quality assessment.
    private var lastSatelliteCount: Int? = null

    // GPS quality thresholds for source-level filtering.
    // Fixes older than this are considered stale and their GPS data is suppressed.
    private val maxGpsFixAgeMs: Long = 5_000L
    // Fixes with accuracy worse than this have their GPS data suppressed.
    private val maxSourceAccuracyM: Float = 50.0f

    // Individual sensor timestamps for accurate time attribution.
    // When emitSample() fires, the sample timestamp reflects the most recent
    // sensor event rather than the emit call time.
    private var lastGpsTimestampMs: Long = 0L
    private var lastPressureTimestampMs: Long = 0L
    private var lastHeadingTimestampMs: Long = 0L

    // Rate-limiting for pressure-triggered emissions: at most every ~500ms.
    // Barometer fires at ~5 Hz (SENSOR_DELAY_NORMAL = 200ms) but we don't
    // need that density for altitude tracking during GPS gaps.
    private var lastPressureEmitTimestampMs: Long = 0L

    @SuppressLint("MissingPermission")
    override fun start(): EmptyResult<SensorError> {
        if (!hasLocationPermission()) {
            return Result.Error(SensorError.Permission.LOCATION_DENIED)
        }

        if (locationManager.getProvider(LocationManager.GPS_PROVIDER) == null) {
            return Result.Error(SensorError.Hardware.GPS_MISSING)
        }

        if (locationListener != null) return Result.Success(Unit)

        val localPressureListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.values.isEmpty()) return
                lastPressureHpa = event.values[0]
                lastPressureTimestampMs = System.currentTimeMillis()

                // Emit sample from pressure sensor so altitude updates even
                // when GPS is unavailable (tunnels, dense tree cover).
                // Rate-limited to ~2 Hz to avoid flooding the flow.
                val now = System.currentTimeMillis()
                if (now - lastPressureEmitTimestampMs >= 500L) {
                    lastPressureEmitTimestampMs = now
                    emitSample()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val localOrientationListener = object : SensorEventListener {
            private var gravity: FloatArray? = null
            private var geomagnetic: FloatArray? = null

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) gravity = event.values.clone()
                if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) geomagnetic = event.values.clone()

                // Suppress heading while the magnetometer is known to be miscalibrated.
                // A figure-eight calibration is needed; until then the azimuth is
                // garbage and would point the compass in a random direction.
                // (GPS course-over-ground, set in the location listener, is unaffected.)
                if (isMagnetometerUnreliable) return

                if (gravity != null && geomagnetic != null) {
                    val r = FloatArray(9)
                    val i = FloatArray(9)
                    if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(r, orientation)
                        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        // Convert magnetic azimuth to a true-north heading.
                        val magneticHeading = (azimuth + 360f) % 360f
                        val trueHeading = (magneticHeading + magneticDeclinationDeg + 360f) % 360f

                        // Circular EMA: blend along the shortest arc so the filter
                        // doesn't lurch the long way around the 0/360 wrap point.
                        val smoothed = smoothedHeadingDeg?.let { prev ->
                            val delta = angularDifference(prev, trueHeading)
                            (prev + headingSmoothingAlpha * delta + 360f) % 360f
                        } ?: trueHeading
                        smoothedHeadingDeg = smoothed
                        lastHeading = smoothed
                        lastHeadingTimestampMs = System.currentTimeMillis()

                        // Throttle emissions to ~2° steps to avoid flooding the
                        // sample flow (and the E-Ink redraw) with micro-changes.
                        val emitted = lastEmittedHeadingDeg
                        if (emitted == null || Math.abs(angularDifference(emitted, smoothed)) >= headingEmitThresholdDeg) {
                            lastEmittedHeadingDeg = smoothed
                            emitSample()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Track magnetometer calibration health. UNRELIABLE/LOW mean the
                // compass needs recalibration and its heading can't be trusted.
                if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    isMagnetometerUnreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                        accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                }
            }
        }

        val localLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                lastGpsTimestampMs = System.currentTimeMillis()
                // Refresh magnetic declination so the magnetometer heading can be
                // corrected to true north.
                magneticDeclinationDeg = android.hardware.GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    if (location.hasAltitude()) location.altitude.toFloat() else 0f,
                    location.time
                ).declination
                emitSample()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        pressureListener = localPressureListener
        locationListener = localLocationListener
        orientationListener = localOrientationListener

        pressureSensor?.also {
            sensorManager.registerListener(
                localPressureListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                callbackHandler
            )
        }

        accelerometer?.also {
            sensorManager.registerListener(
                localOrientationListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                callbackHandler
            )
        }

        magnetometer?.also {
            sensorManager.registerListener(
                localOrientationListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                callbackHandler
            )
        }

        try {
            // GPS: 1-second intervals with 2.0m minimum distance.
            // At 30 km/h (8.3 m/s), this gives ~1 update every 4m of travel,
            // which is appropriate for cycling accuracy needs.
            // The 0.5m minimum was overly aggressive and could cause excessive
            // callbacks on devices with higher GPS update rates.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                2.0f,
                localLocationListener,
                callbackHandler.looper
            )
        } catch (e: SecurityException) {
            return Result.Error(SensorError.Permission.LOCATION_DENIED)
        }

        // Register GnssStatus callback for satellite count tracking.
        // Used to assess GPS fix quality beyond raw accuracy.
        val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var usedCount = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) usedCount++
                }
                lastSatelliteCount = usedCount
            }
        }
        gnssStatusCallback = gnssCallback
        locationManager.registerGnssStatusCallback(gnssCallback, callbackHandler)

        return Result.Success(Unit)
    }

    private fun emitSample() {
        val location = lastLocation
        val pressureHpa = lastPressureHpa
        val altitudeFromBarometer = pressureHpa?.let {
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
        val sampleTimestampMs = maxOf(
            lastGpsTimestampMs,
            lastPressureTimestampMs,
            lastHeadingTimestampMs,
            now // fallback
        )

        // Bearing source: while moving, GPS course-over-ground is far more
        // reliable than the magnetometer (which is distorted by the bike frame
        // and the phone's own fields). When slow or stopped, fall back to the
        // smoothed magnetometer heading so the compass still points somewhere.
        val gpsBearing = if (useGpsData) {
            location?.let {
                if (it.hasBearing() && it.hasSpeed() && it.speed >= gpsBearingMinSpeedMps) it.bearing else null
            }
        } else null
        // Drop NaN/Infinity (rotation-matrix or driver glitches) and normalize to
        // [0, 360) so downstream consumers never see an out-of-range heading.
        val bearing = (gpsBearing ?: lastHeading)
            ?.takeIf { it.isFinite() }
            ?.let { ((it % 360f) + 360f) % 360f }

        _samples.tryEmit(
            RideSensorSample(
                timestampMs = sampleTimestampMs,
                latitude = if (useGpsData) location?.latitude else null,
                longitude = if (useGpsData) location?.longitude else null,
                altitudeFromGpsM = if (useGpsData) location?.let { if (it.hasAltitude()) it.altitude else null } else null,
                altitudeFromBarometerM = altitudeFromBarometer,
                speedFromGpsMps = if (useGpsData) location?.let { if (it.hasSpeed()) it.speed.toDouble() else null } else null,
                accuracyM = if (useGpsData) location?.let { if (it.hasAccuracy()) it.accuracy else null } else null,
                bearingDegrees = bearing,
                satelliteCount = if (useGpsData) lastSatelliteCount else null,
                pressureHpa = pressureHpa?.toDouble()
            )
        )
    }

    override fun stop() {
        locationListener?.let { listener ->
            locationManager.removeUpdates(listener)
        }
        pressureListener?.let { listener ->
            sensorManager.unregisterListener(listener)
        }
        orientationListener?.let { listener ->
            sensorManager.unregisterListener(listener)
        }
        gnssStatusCallback?.let { callback ->
            locationManager.unregisterGnssStatusCallback(callback)
        }
        locationListener = null
        pressureListener = null
        orientationListener = null
        gnssStatusCallback = null
        lastLocation = null
        lastPressureHpa = null
        lastHeading = null
        smoothedHeadingDeg = null
        lastEmittedHeadingDeg = null
        magneticDeclinationDeg = 0f
        isMagnetometerUnreliable = false
        lastSatelliteCount = null
        lastGpsTimestampMs = 0L
        lastPressureTimestampMs = 0L
        lastHeadingTimestampMs = 0L
        lastPressureEmitTimestampMs = 0L
    }

    /**
     * Shortest signed angular difference from [from] to [to], in degrees,
     * within (-180, 180]. Used so circular EMA and threshold checks move along
     * the short arc across the 0/360 wrap point.
     */
    private fun angularDifference(from: Float, to: Float): Float {
        var diff = (to - from + 540f) % 360f - 180f
        if (diff == -180f) diff = 180f
        return diff
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }
}
