package com.speedevand.inkride.core.domain.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Result of feeding one raw GPS fix into [PositionKalmanFilter]: the filtered
 * position and the velocity-derived speed/bearing. On a gated (rejected) fix,
 * this is a dead-reckoned prediction from the last known velocity, not the
 * raw (likely erroneous) input.
 */
data class FilteredPosition(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val bearingDegrees: Float?,
    // True when this fix's raw position was statistically implausible given
    // the filter's current velocity/uncertainty estimate and was replaced by
    // a dead-reckoned prediction instead of being incorporated.
    val wasGated: Boolean,
)

/**
 * A constant-velocity Kalman filter that smooths raw GPS fixes before they
 * reach [RideMetricsCalculator]. Runs in a local equirectangular (east/north,
 * meters) tangent plane anchored at the first accepted fix, since lat/lon
 * degrees are not uniform distance units.
 *
 * State vector: [eastM, northM, velEastMps, velNorthMps]. Measurement noise
 * is derived per-fix from the reported GPS accuracy (variance = accuracy²),
 * so a tight fix is trusted more than a loose one. A fix whose innovation
 * (difference between predicted and measured position) is statistically
 * implausible — beyond [gateChiSquareThreshold], the ~99% bound for 2 degrees
 * of freedom — is gated: the filter predicts through it via dead-reckoning
 * instead of incorporating the measurement, the formal analogue of
 * [RideMetricsCalculator]'s ad-hoc bounce/jump detection.
 *
 * This class knows nothing about [RideMetricsCalculator] or vice versa — it's
 * consumed one layer up, in the Android-facing sensor data source, before a
 * [RideSensorSample] is built. That keeps [RideMetricsCalculator]'s existing
 * test suite completely unaffected by this filter's introduction.
 */
class PositionKalmanFilter(
    // Process noise spectral density ((m/s²)² per second) — how much the
    // filter expects true velocity to vary between fixes. Higher trusts new
    // measurements more; lower trusts the constant-velocity prediction more.
    // 3.0 is a moderate middle ground for cycling's typical accelerations.
    private val processNoiseDensity: Double = 3.0,
    // Chi-square bound for 2 degrees of freedom at ~99% confidence — a fix
    // whose innovation exceeds this is treated as an outlier.
    private val gateChiSquareThreshold: Double = 9.21,
    // Floor on the per-fix measurement-noise standard deviation (meters), so
    // a GPS accuracy reading of 0 can't collapse the filter onto a single
    // point with false certainty.
    private val minAccuracyM: Double = 2.0,
    // Upper bound on the elapsed time between fixes used for prediction, so a
    // fix returning after a long GPS dropout doesn't blow up the process-noise
    // terms (which scale with dt^3/dt^4).
    private val maxDtSeconds: Double = 10.0,
) {
    private var originLatDeg: Double? = null
    private var originLonDeg: Double? = null
    private var metersPerDegLat: Double = 0.0
    private var metersPerDegLon: Double = 0.0

    // State: [eastM, northM, velEastMps, velNorthMps].
    private var state: DoubleArray? = null

    // 4x4 state covariance.
    private var covariance: Array<DoubleArray>? = null
    private var lastTimestampMs: Long? = null

    /**
     * Feeds one raw GPS fix (already passed the source's freshness/accuracy
     * gate). [accuracyM] is the fix's reported horizontal accuracy in meters.
     */
    fun update(
        latitude: Double,
        longitude: Double,
        accuracyM: Float,
        timestampMs: Long,
    ): FilteredPosition {
        val prevState = state
        val prevCovariance = covariance
        val prevTimestampMs = lastTimestampMs

        if (prevState == null || prevCovariance == null || prevTimestampMs == null) {
            originLatDeg = latitude
            originLonDeg = longitude
            metersPerDegLat = 111_320.0
            metersPerDegLon = 111_320.0 * cos(Math.toRadians(latitude))
            state = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            val initialPositionVarianceM2 = accuracyVariance(accuracyM)
            covariance =
                arrayOf(
                    doubleArrayOf(initialPositionVarianceM2, 0.0, 0.0, 0.0),
                    doubleArrayOf(0.0, initialPositionVarianceM2, 0.0, 0.0),
                    doubleArrayOf(0.0, 0.0, 25.0, 0.0),
                    doubleArrayOf(0.0, 0.0, 0.0, 25.0),
                )
            lastTimestampMs = timestampMs
            return FilteredPosition(latitude, longitude, speedMps = 0.0, bearingDegrees = null, wasGated = false)
        }

        val dtSeconds = (((timestampMs - prevTimestampMs).coerceAtLeast(0L)) / 1000.0).coerceAtMost(maxDtSeconds)

        // Predict.
        val f = transitionMatrix(dtSeconds)
        val predictedState = matVecMul(f, prevState)
        val q = processNoiseMatrix(dtSeconds)
        val predictedCovariance = matAdd(matMul(matMul(f, prevCovariance), matTranspose(f)), q)

        // Measurement: this fix's position in the local ENU plane.
        val measuredEastM = (longitude - originLonDeg!!) * metersPerDegLon
        val measuredNorthM = (latitude - originLatDeg!!) * metersPerDegLat
        val positionVarianceM2 = accuracyVariance(accuracyM)

        // Innovation (2D: east, north).
        val innovationEast = measuredEastM - predictedState[0]
        val innovationNorth = measuredNorthM - predictedState[1]

        // Innovation covariance S = H P' H^T + R, restricted to the position
        // block of the covariance (H selects [eastM, northM]).
        val sEE = predictedCovariance[0][0] + positionVarianceM2
        val sEN = predictedCovariance[0][1]
        val sNE = predictedCovariance[1][0]
        val sNN = predictedCovariance[1][1] + positionVarianceM2

        val det = sEE * sNN - sEN * sNE
        val safeDet = if (det == 0.0) 1e-9 else det
        val sInvEE = sNN / safeDet
        val sInvEN = -sEN / safeDet
        val sInvNE = -sNE / safeDet
        val sInvNN = sEE / safeDet

        val mahalanobisSquared =
            innovationEast * (sInvEE * innovationEast + sInvEN * innovationNorth) +
                innovationNorth * (sInvNE * innovationEast + sInvNN * innovationNorth)

        val isGated = mahalanobisSquared > gateChiSquareThreshold

        val (finalState, finalCovariance) =
            if (isGated) {
                predictedState to predictedCovariance
            } else {
                // Kalman gain K = P' H^T S^-1. H only selects the first two
                // state components, so P' H^T reduces to the first two
                // columns of P'.
                val gain = Array(4) { DoubleArray(2) }
                for (row in 0 until 4) {
                    val pRowEast = predictedCovariance[row][0]
                    val pRowNorth = predictedCovariance[row][1]
                    gain[row][0] = pRowEast * sInvEE + pRowNorth * sInvNE
                    gain[row][1] = pRowEast * sInvEN + pRowNorth * sInvNN
                }
                val updatedState = DoubleArray(4)
                for (row in 0 until 4) {
                    updatedState[row] =
                        predictedState[row] + gain[row][0] * innovationEast + gain[row][1] * innovationNorth
                }
                // P = (I - K H) P' — K H's only nonzero columns are 0 and 1.
                val updatedCovariance = Array(4) { DoubleArray(4) }
                for (row in 0 until 4) {
                    for (col in 0 until 4) {
                        val khTerm = gain[row][0] * predictedCovariance[0][col] + gain[row][1] * predictedCovariance[1][col]
                        updatedCovariance[row][col] = predictedCovariance[row][col] - khTerm
                    }
                }
                updatedState to updatedCovariance
            }

        state = finalState
        covariance = finalCovariance
        lastTimestampMs = timestampMs

        val filteredLat = originLatDeg!! + finalState[1] / metersPerDegLat
        val filteredLon = originLonDeg!! + finalState[0] / metersPerDegLon
        val velEast = finalState[2]
        val velNorth = finalState[3]
        val speedMps = sqrt(velEast * velEast + velNorth * velNorth)
        val bearingDegrees =
            if (speedMps > 0.1) {
                ((Math.toDegrees(atan2(velEast, velNorth)) + 360.0) % 360.0).toFloat()
            } else {
                null
            }

        return FilteredPosition(filteredLat, filteredLon, speedMps, bearingDegrees, isGated)
    }

    /** Clears all filter state, e.g. when a ride stops or GPS is restarted. */
    fun reset() {
        originLatDeg = null
        originLonDeg = null
        metersPerDegLat = 0.0
        metersPerDegLon = 0.0
        state = null
        covariance = null
        lastTimestampMs = null
    }

    private fun accuracyVariance(accuracyM: Float): Double {
        val clamped = max(accuracyM.toDouble(), minAccuracyM)
        return clamped * clamped
    }

    private fun transitionMatrix(dtSeconds: Double): Array<DoubleArray> =
        arrayOf(
            doubleArrayOf(1.0, 0.0, dtSeconds, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, dtSeconds),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0),
        )

    /** Discretized white-noise-acceleration process noise for a constant-velocity model. */
    private fun processNoiseMatrix(dtSeconds: Double): Array<DoubleArray> {
        val dt2 = dtSeconds * dtSeconds
        val dt3 = dt2 * dtSeconds
        val dt4 = dt3 * dtSeconds
        val q = processNoiseDensity
        return arrayOf(
            doubleArrayOf(q * dt4 / 4.0, 0.0, q * dt3 / 2.0, 0.0),
            doubleArrayOf(0.0, q * dt4 / 4.0, 0.0, q * dt3 / 2.0),
            doubleArrayOf(q * dt3 / 2.0, 0.0, q * dt2, 0.0),
            doubleArrayOf(0.0, q * dt3 / 2.0, 0.0, q * dt2),
        )
    }

    private fun matVecMul(
        m: Array<DoubleArray>,
        v: DoubleArray,
    ): DoubleArray =
        DoubleArray(m.size) { row ->
            var sum = 0.0
            for (col in v.indices) sum += m[row][col] * v[col]
            sum
        }

    private fun matMul(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>,
    ): Array<DoubleArray> {
        val rows = a.size
        val cols = b[0].size
        val inner = b.size
        return Array(rows) { row ->
            DoubleArray(cols) { col ->
                var sum = 0.0
                for (k in 0 until inner) sum += a[row][k] * b[k][col]
                sum
            }
        }
    }

    private fun matTranspose(m: Array<DoubleArray>): Array<DoubleArray> {
        val rows = m.size
        val cols = m[0].size
        return Array(cols) { col -> DoubleArray(rows) { row -> m[row][col] } }
    }

    private fun matAdd(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>,
    ): Array<DoubleArray> = Array(a.size) { row -> DoubleArray(a[row].size) { col -> a[row][col] + b[row][col] } }
}
