package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class RouteFollowerTest {
    private val follower = RouteFollower(offRouteThresholdM = 50.0)

    // A ~685 m straight segment running east along latitude 52.0.
    private val route =
        PlannedRoute(
            name = "Test",
            points = listOf(RoutePoint(52.0, 21.0), RoutePoint(52.0, 21.01)),
            waypoints = listOf(RouteWaypoint(52.0, 21.01, "Finish")),
        )

    @Test
    fun `a point on the line is not off route`() {
        val progress = follower.evaluate(route, latitude = 52.0, longitude = 21.005)

        assertThat(progress.distanceToRouteM).isCloseTo(0.0, 1.0)
        assertThat(progress.isOffRoute).isFalse()
    }

    @Test
    fun `a point off the line reports the perpendicular distance`() {
        // ~0.001° latitude north of the line ≈ 111 m.
        val progress = follower.evaluate(route, latitude = 52.001, longitude = 21.005)

        assertThat(progress.distanceToRouteM).isCloseTo(111.0, 8.0)
        assertThat(progress.isOffRoute).isTrue()
    }

    @Test
    fun `next waypoint distance is measured along the route`() {
        // From the midpoint, the finish lies ~half the segment away (~342 m).
        val progress = follower.evaluate(route, latitude = 52.0, longitude = 21.005)

        assertThat(progress.nextWaypointName).isEqualTo("Finish")
        assertThat(progress.distanceToNextWaypointM).isNotNull().isCloseTo(342.0, 15.0)
    }

    @Test
    fun `once every named turn is behind, it shows distance to the route end`() {
        // The only waypoint sits at the very start, so from the midpoint the
        // rider has passed it; the readout must fall back to the finish.
        val startWaypoint =
            route.copy(
                waypoints = listOf(RouteWaypoint(52.0, 21.0, "Start")),
            )

        val progress = follower.evaluate(startWaypoint, latitude = 52.0, longitude = 21.005)

        assertThat(progress.nextWaypointName).isNull()
        assertThat(progress.distanceToNextWaypointM).isNotNull().isCloseTo(342.0, 15.0)
    }

    @Test
    fun `with no waypoints it falls back to the route end`() {
        val noWaypoints = route.copy(waypoints = emptyList())

        val progress = follower.evaluate(noWaypoints, latitude = 52.0, longitude = 21.0)

        assertThat(progress.nextWaypointName).isNull()
        // Full segment length from the start.
        assertThat(progress.distanceToNextWaypointM).isNotNull().isCloseTo(685.0, 25.0)
    }

    @Test
    fun `an empty route reports zero distance and is never off route`() {
        val empty = route.copy(points = emptyList())

        val progress = follower.evaluate(empty, latitude = 52.0, longitude = 21.005)

        assertThat(progress.distanceToRouteM).isEqualTo(0.0)
        assertThat(progress.isOffRoute).isFalse()
        assertThat(progress.distanceToNextWaypointM).isNull()
        assertThat(progress.nextWaypointName).isNull()
    }

    @Test
    fun `a single-point route reports direct distance to that point and its waypoint`() {
        val singlePoint =
            route.copy(
                points = listOf(RoutePoint(52.0, 21.0)),
                waypoints = listOf(RouteWaypoint(52.0, 21.001, "Only")),
            )

        val onPoint = follower.evaluate(singlePoint, latitude = 52.0, longitude = 21.0)
        assertThat(onPoint.distanceToRouteM).isEqualTo(0.0)
        assertThat(onPoint.isOffRoute).isFalse()
        assertThat(onPoint.nextWaypointName).isEqualTo("Only")
        // Direct haversine to the waypoint, not an along-route distance --
        // there's no polyline to measure along with a single point.
        assertThat(onPoint.distanceToNextWaypointM).isNotNull().isCloseTo(68.5, 5.0)

        val awayFromPoint = follower.evaluate(singlePoint, latitude = 52.001, longitude = 21.0)
        assertThat(awayFromPoint.distanceToRouteM).isCloseTo(111.0, 8.0)
        assertThat(awayFromPoint.isOffRoute).isTrue()
    }

    @Test
    fun `a single-point route with no waypoints reports no next waypoint`() {
        val singlePointNoWaypoints =
            route.copy(points = listOf(RoutePoint(52.0, 21.0)), waypoints = emptyList())

        val progress = follower.evaluate(singlePointNoWaypoints, latitude = 52.0, longitude = 21.0)

        assertThat(progress.nextWaypointName).isNull()
        assertThat(progress.distanceToNextWaypointM).isNull()
    }

    @Test
    fun `duplicate consecutive route points produce a finite distance, not NaN`() {
        val duplicatePoints =
            route.copy(points = listOf(RoutePoint(52.0, 21.0), RoutePoint(52.0, 21.0)))

        val progress = follower.evaluate(duplicatePoints, latitude = 52.001, longitude = 21.0)

        assertThat(progress.distanceToRouteM.isNaN()).isFalse()
        // The zero-length segment's projection collapses to point A, so the
        // reported distance is the direct distance to that (duplicated) point.
        assertThat(progress.distanceToRouteM).isCloseTo(111.0, 8.0)
    }
}
