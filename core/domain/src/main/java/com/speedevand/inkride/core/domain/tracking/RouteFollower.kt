package com.speedevand.inkride.core.domain.tracking

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live progress along a loaded [PlannedRoute].
 *
 * [distanceToRouteM] is the perpendicular distance from the rider to the nearest
 * point on the route polyline; [isOffRoute] is true once that exceeds the
 * follower's threshold. [distanceToNextWaypointM] is measured *along the route*
 * to the next turn marker ahead (or to the route's end when the file has no
 * waypoints), null only when the route has no points at all.
 */
data class RouteProgress(
    val distanceToRouteM: Double,
    val isOffRoute: Boolean,
    val distanceToNextWaypointM: Double? = null,
    val nextWaypointName: String? = null,
)

/**
 * Pure route-following calculator: given the rider's current fix and a
 * [PlannedRoute], it snaps them to the nearest point on the route, reports how
 * far off-route they are, and how far along the route the next turn lies.
 *
 * Distances use a local equirectangular projection for the perpendicular (exact
 * enough over the few-metre scale of a single segment) and haversine for
 * along-route lengths.
 */
class RouteFollower(
    private val offRouteThresholdM: Double = 50.0,
) {
    // Per-route precomputation (cumulative distances + waypoint/finish positions
    // along the route) cached by route identity, so the work that never changes
    // while following a route is done once at load rather than on every GPS fix.
    // Only ever touched from RideTracker's single sample-collection coroutine.
    private var prepared: Prepared? = null

    fun evaluate(
        route: PlannedRoute,
        latitude: Double,
        longitude: Double,
    ): RouteProgress {
        val pts = route.points
        if (pts.isEmpty()) {
            return RouteProgress(distanceToRouteM = 0.0, isOffRoute = false)
        }
        if (pts.size == 1) {
            val d = haversine(latitude, longitude, pts[0].latitude, pts[0].longitude)
            val next = route.waypoints.firstOrNull()
            return RouteProgress(
                distanceToRouteM = d,
                isOffRoute = d > offRouteThresholdM,
                distanceToNextWaypointM =
                    next
                        ?.let { haversine(latitude, longitude, it.latitude, it.longitude) },
                nextWaypointName = next?.name,
            )
        }

        val prep = prepared(route, pts)
        val snap = snap(pts, prep.cumulative, latitude, longitude)
        val next = nextWaypoint(prep.waypointMarkers, snap.cumulativeM)

        return RouteProgress(
            distanceToRouteM = snap.distanceM,
            isOffRoute = snap.distanceM > offRouteThresholdM,
            distanceToNextWaypointM = next?.distanceM,
            nextWaypointName = next?.name,
        )
    }

    private class Prepared(
        val route: PlannedRoute,
        val cumulative: DoubleArray,
        // Each turn marker's along-route position + name, with the route end as a
        // trailing unnamed fallback so the readout shows distance-to-finish once
        // every named turn is behind the rider.
        val waypointMarkers: List<Pair<Double, String?>>,
    )

    private data class Snap(
        val distanceM: Double,
        val cumulativeM: Double,
    )

    private data class NextWaypoint(
        val distanceM: Double,
        val name: String?,
    )

    private data class Projection(
        val distanceM: Double,
        val t: Double,
    )

    private fun prepared(
        route: PlannedRoute,
        pts: List<RoutePoint>,
    ): Prepared {
        prepared?.let { if (it.route === route) return it }
        val cumulative = cumulativeDistances(pts)
        val markers =
            buildList {
                route.waypoints.forEach { wp ->
                    add(snap(pts, cumulative, wp.latitude, wp.longitude).cumulativeM to wp.name)
                }
                add(cumulative.last() to null)
            }
        return Prepared(route, cumulative, markers).also { prepared = it }
    }

    /** Cumulative along-route distance to each point; element 0 is 0. */
    private fun cumulativeDistances(pts: List<RoutePoint>): DoubleArray {
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] +
                haversine(
                    pts[i - 1].latitude,
                    pts[i - 1].longitude,
                    pts[i].latitude,
                    pts[i].longitude,
                )
        }
        return cum
    }

    /** Nearest point on the polyline to (lat, lon): its distance and along-route position. */
    private fun snap(
        pts: List<RoutePoint>,
        cumulative: DoubleArray,
        latitude: Double,
        longitude: Double,
    ): Snap {
        var bestDistance = Double.MAX_VALUE
        var bestCumulative = 0.0
        for (i in 0 until pts.size - 1) {
            val proj = projectToSegment(latitude, longitude, pts[i], pts[i + 1])
            if (proj.distanceM < bestDistance) {
                bestDistance = proj.distanceM
                val segmentLength = cumulative[i + 1] - cumulative[i]
                bestCumulative = cumulative[i] + proj.t * segmentLength
            }
        }
        return Snap(bestDistance, bestCumulative)
    }

    /**
     * The next turn marker ahead of the rider, measured along the route — or the
     * route end (unnamed) once every named turn is behind them, so the readout
     * keeps showing distance-to-finish to the end of the ride.
     */
    private fun nextWaypoint(
        markers: List<Pair<Double, String?>>,
        currentCumulative: Double,
    ): NextWaypoint? =
        markers
            .filter { it.first > currentCumulative + AHEAD_EPSILON_M }
            .minByOrNull { it.first }
            ?.let { NextWaypoint(distanceM = it.first - currentCumulative, name = it.second) }

    /** Projects P=(lat,lon) onto segment a→b in a local metre plane centred at P. */
    private fun projectToSegment(
        latitude: Double,
        longitude: Double,
        a: RoutePoint,
        b: RoutePoint,
    ): Projection {
        val cosLat = cos(latitude.toRadians())
        val ax = (a.longitude - longitude) * cosLat * METERS_PER_DEGREE
        val ay = (a.latitude - latitude) * METERS_PER_DEGREE
        val bx = (b.longitude - longitude) * cosLat * METERS_PER_DEGREE
        val by = (b.latitude - latitude) * METERS_PER_DEGREE
        val abx = bx - ax
        val aby = by - ay
        val lengthSq = abx * abx + aby * aby
        // P is the origin; t is the clamped projection of P onto a→b.
        val t = if (lengthSq == 0.0) 0.0 else ((-ax * abx - ay * aby) / lengthSq).coerceIn(0.0, 1.0)
        val cx = ax + t * abx
        val cy = ay + t * aby
        return Projection(distanceM = sqrt(cx * cx + cy * cy), t = t)
    }

    private fun haversine(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a =
            sin(dLat / 2).pow(2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_M * 2 * asin(min(1.0, sqrt(a)))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val METERS_PER_DEGREE = PI / 180.0 * EARTH_RADIUS_M

        // Ignore turn markers essentially at the rider's feet so the readout
        // advances to the genuinely-next turn rather than sticking at ~0 m.
        const val AHEAD_EPSILON_M = 5.0
    }
}
