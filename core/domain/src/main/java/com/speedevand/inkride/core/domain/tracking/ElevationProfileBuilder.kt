package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.history.RideTrackPoint
import kotlin.math.min

data class ElevationProfilePoint(
    val distanceKm: Double,
    val altitudeM: Double,
)

data class ElevationProfile(
    val points: List<ElevationProfilePoint>,
    val minAltitudeM: Double,
    val maxAltitudeM: Double,
    val minAltitudeDistanceKm: Double,
    val maxAltitudeDistanceKm: Double,
)

private const val DEFAULT_MAX_SAMPLES = 200

/**
 * Turns a ride's recorded track into a distance-indexed altitude profile for
 * charting. Points without altitude are dropped before distance is
 * accumulated, so distance is measured only between consecutive points that
 * actually have a reading. Min/max are found on the full (pre-downsample)
 * series so a real peak can never be lost or mis-positioned by decimation.
 * Returns null when fewer than 2 points have altitude data.
 */
fun buildElevationProfile(
    points: List<RideTrackPoint>,
    maxSamples: Int = DEFAULT_MAX_SAMPLES,
): ElevationProfile? {
    val withAltitude = points.filter { it.altitudeM != null }
    if (withAltitude.size < 2) return null

    val series = mutableListOf(0.0 to withAltitude[0].altitudeM!!)
    var cumulativeM = 0.0
    for (i in 1 until withAltitude.size) {
        val prev = withAltitude[i - 1]
        val curr = withAltitude[i]
        cumulativeM += haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
        series.add(cumulativeM / 1000.0 to curr.altitudeM!!)
    }

    val minEntry = series.minBy { it.second }
    val maxEntry = series.maxBy { it.second }

    val downsampled =
        if (series.size <= maxSamples) {
            series.map { ElevationProfilePoint(it.first, it.second) }
        } else {
            bucketAverage(series, maxSamples)
        }

    return ElevationProfile(
        points = downsampled,
        minAltitudeM = minEntry.second,
        maxAltitudeM = maxEntry.second,
        minAltitudeDistanceKm = minEntry.first,
        maxAltitudeDistanceKm = maxEntry.first,
    )
}

private fun bucketAverage(
    series: List<Pair<Double, Double>>,
    bucketCount: Int,
): List<ElevationProfilePoint> {
    val totalDistanceKm = series.last().first
    if (totalDistanceKm <= 0.0) {
        // All points at the same position (e.g. a stationary trainer ride) —
        // bucketing by distance would divide by zero, so collapse to one point.
        return listOf(ElevationProfilePoint(0.0, series.map { it.second }.average()))
    }
    val bucketWidthKm = totalDistanceKm / bucketCount
    val buckets = Array(bucketCount) { mutableListOf<Pair<Double, Double>>() }
    series.forEach { (distanceKm, altitudeM) ->
        val index = min(bucketCount - 1, (distanceKm / bucketWidthKm).toInt())
        buckets[index].add(distanceKm to altitudeM)
    }
    return buckets.filter { it.isNotEmpty() }.map { bucket ->
        ElevationProfilePoint(
            distanceKm = bucket.map { it.first }.average(),
            altitudeM = bucket.map { it.second }.average(),
        )
    }
}
