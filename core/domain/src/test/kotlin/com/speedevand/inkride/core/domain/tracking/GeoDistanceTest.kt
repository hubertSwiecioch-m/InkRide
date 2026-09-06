package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThan
import org.junit.jupiter.api.Test

class GeoDistanceTest {
    @Test
    fun `same point has zero distance`() {
        assertThat(haversineMeters(52.0, 21.0, 52.0, 21.0)).isEqualTo(0.0)
    }

    @Test
    fun `one hundredth of a degree of latitude is about 1112 meters`() {
        val distance = haversineMeters(52.0, 21.0, 52.01, 21.0)

        assertThat(distance).isCloseTo(1111.95, 1.0)
    }

    @Test
    fun `distance is symmetric regardless of point order`() {
        val aToB = haversineMeters(52.0, 21.0, 48.5, 19.5)
        val bToA = haversineMeters(48.5, 19.5, 52.0, 21.0)

        assertThat(aToB).isCloseTo(bToA, 0.0001)
    }

    @Test
    fun `handles antimeridian crossing without an inflated distance`() {
        // 0.002 degrees of angular separation the short way across +-180 deg,
        // not the ~360 degree difference a naive lon2-lon1 would suggest.
        val distance = haversineMeters(52.0, 179.999, 52.0, -179.999)

        assertThat(distance).isLessThan(1_000.0)
    }

    @Test
    fun `handles points near a pole without an inflated distance`() {
        // Same latitude, opposite longitudes -- physically close together near
        // the pole even though the longitudes are 180 degrees apart.
        val distance = haversineMeters(89.999, 0.0, 89.999, 180.0)

        assertThat(distance).isLessThan(500.0)
    }

    @Test
    fun `never returns NaN for near-antipodal points, exercising the sqrt(a) clamp`() {
        data class Case(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double)

        val nearAntipodalCases =
            listOf(
                Case(52.0, 21.0, -52.0, -159.0),
                Case(0.0, 0.0, 0.0, 180.0),
                Case(45.0, 90.0, -45.0, -90.0),
                Case(10.0, 170.0, -10.0, -10.0),
            )

        nearAntipodalCases.forEach { case ->
            val distance = haversineMeters(case.lat1, case.lon1, case.lat2, case.lon2)
            assertThat(distance.isNaN()).isFalse()
        }

        // Exact antipodal points: the theoretical max distance, half the
        // modeled Earth's circumference (radius 6_371_000m).
        val antipodal = haversineMeters(0.0, 0.0, 0.0, 180.0)
        assertThat(antipodal).isCloseTo(20_015_086.8, 10.0)
    }

    @Test
    fun `works correctly in the southern and western hemispheres`() {
        val distance = haversineMeters(-33.5, -70.25, -33.51, -70.25)

        assertThat(distance).isCloseTo(1111.95, 1.0)
    }
}
