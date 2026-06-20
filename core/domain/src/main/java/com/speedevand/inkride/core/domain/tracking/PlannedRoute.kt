package com.speedevand.inkride.core.domain.tracking

/** A single ordered point along a loaded route (the line the rider follows). */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * A named point of interest on a route — typically a turn instruction (GPX
 * `<wpt>` / named `<rtept>`). Used to derive the dashboard's "next turn" readout.
 */
data class RouteWaypoint(
    val latitude: Double,
    val longitude: Double,
    val name: String? = null,
)

/**
 * A route loaded from a GPX file for the rider to follow.
 *
 * [points] is the ordered polyline used for off-route detection; [waypoints] are
 * the turn/POI markers used for the "next turn in X" readout. Either may be
 * empty depending on the source file. Pure domain model — parsed by
 * [GpxRouteParser] and consumed by [RouteFollower].
 */
data class PlannedRoute(
    val name: String? = null,
    val points: List<RoutePoint> = emptyList(),
    val waypoints: List<RouteWaypoint> = emptyList(),
)
