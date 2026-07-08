package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import org.junit.jupiter.api.Test

class ElevationProfileBuilderTest {
    @Test
    fun `empty list returns null`() {
        assertThat(buildElevationProfile(emptyList())).isNull()
    }

    @Test
    fun `single point returns null`() {
        val points = listOf(RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0))

        assertThat(buildElevationProfile(points)).isNull()
    }

    @Test
    fun `all points missing altitude returns null`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = null),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = null),
            )

        assertThat(buildElevationProfile(points)).isNull()
    }

    @Test
    fun `points missing altitude are excluded from the series`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.005, longitude = 21.0, altitudeM = null),
                RideTrackPoint(timestampMs = 2000L, latitude = 52.01, longitude = 21.0, altitudeM = 110.0),
            )

        val profile = buildElevationProfile(points)

        assertThat(profile).isNotNull()
        assertThat(profile!!.points).hasSize(2)
        assertThat(profile.points[0].distanceKm).isCloseTo(0.0, 0.001)
        assertThat(profile.points[1].distanceKm).isCloseTo(1.112, 0.01)
    }

    @Test
    fun `min and max altitude are found with their distance position`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = 150.0),
                RideTrackPoint(timestampMs = 2000L, latitude = 52.02, longitude = 21.0, altitudeM = 80.0),
            )

        val profile = buildElevationProfile(points)!!

        assertThat(profile.maxAltitudeM).isEqualTo(150.0)
        assertThat(profile.maxAltitudeDistanceKm).isCloseTo(1.112, 0.01)
        assertThat(profile.minAltitudeM).isEqualTo(80.0)
        assertThat(profile.minAltitudeDistanceKm).isCloseTo(2.224, 0.02)
    }

    @Test
    fun `a large series is downsampled to at most maxSamples points`() {
        val points =
            (0 until 500).map { i ->
                RideTrackPoint(
                    timestampMs = i * 1000L,
                    latitude = 52.0 + i * 0.0001,
                    longitude = 21.0,
                    altitudeM = 100.0 + i,
                )
            }

        val profile = buildElevationProfile(points, maxSamples = 200)!!

        assertThat(profile.points.size).isLessThan(201)
    }

    @Test
    fun `a series smaller than maxSamples is not downsampled`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = 110.0),
                RideTrackPoint(timestampMs = 2000L, latitude = 52.02, longitude = 21.0, altitudeM = 120.0),
            )

        val profile = buildElevationProfile(points, maxSamples = 200)!!

        assertThat(profile.points).hasSize(3)
    }
}
