package com.speedevand.inkride.core.domain.history

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

class GpxBuilderTest {
    private val ride =
        RideRecord(
            id = 1L,
            startTimestamp = 0L,
            endTimestamp = 3_600_000L,
            distanceKm = 20.0,
            movingTimeSeconds = 3_000L,
            elapsedTimeSeconds = 3_600L,
            averageSpeedKmh = 24.0,
            maxSpeedKmh = 40.0,
            elevationGainM = 150.0,
            caloriesKcal = 500.0,
        )

    private var originalLocale: Locale = Locale.getDefault()

    @BeforeEach
    fun captureLocale() {
        originalLocale = Locale.getDefault()
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `produces a well-formed GPX 1_1 document with the expected tag structure`() {
        val gpx = GpxBuilder.build(ride, emptyList())

        assertThat(gpx).startsWith("""<?xml version="1.0" encoding="UTF-8"?>""")
        assertThat(gpx).contains("""<gpx version="1.1" creator="InkRide" """)
        assertThat(gpx).contains("""xmlns="http://www.topografix.com/GPX/1/1">""")
        assertThat(gpx).contains("<metadata>")
        assertThat(gpx).contains("<trk>")
        assertThat(gpx).contains("<trkseg>")
    }

    @Test
    fun `metadata time matches the ride start timestamp in UTC ISO-8601`() {
        val gpx = GpxBuilder.build(ride, emptyList())

        assertThat(gpx).contains("<time>1970-01-01T00:00:00Z</time>")
    }

    @Test
    fun `track name includes the same formatted start time as the metadata`() {
        val gpx = GpxBuilder.build(ride, emptyList())

        assertThat(gpx).contains("<name>InkRide 1970-01-01T00:00:00Z</name>")
    }

    @Test
    fun `each point emits latitude and longitude formatted to six decimals`() {
        val point = RideTrackPoint(timestampMs = 90_061_000L, latitude = 52.123456, longitude = 21.654321)

        val gpx = GpxBuilder.build(ride, listOf(point))

        assertThat(gpx).contains("""<trkpt lat="52.123456" lon="21.654321">""")
    }

    @Test
    fun `negative latitude and longitude are formatted correctly`() {
        val point = RideTrackPoint(timestampMs = 90_061_000L, latitude = -33.5, longitude = -70.25)

        val gpx = GpxBuilder.build(ride, listOf(point))

        assertThat(gpx).contains("""<trkpt lat="-33.500000" lon="-70.250000">""")
    }

    @Test
    fun `point altitude is included as ele with one decimal when present`() {
        val point =
            RideTrackPoint(timestampMs = 90_061_000L, latitude = 52.0, longitude = 21.0, altitudeM = 120.5)

        val gpx = GpxBuilder.build(ride, listOf(point))

        assertThat(gpx).contains("<ele>120.5</ele>")
    }

    @Test
    fun `point altitude is omitted when null`() {
        val point =
            RideTrackPoint(timestampMs = 90_061_000L, latitude = 52.0, longitude = 21.0, altitudeM = null)

        val gpx = GpxBuilder.build(ride, listOf(point))

        assertThat(gpx).doesNotContain("<ele>")
    }

    @Test
    fun `point time uses the point's own timestamp, not the ride start`() {
        val point = RideTrackPoint(timestampMs = 90_061_000L, latitude = 52.0, longitude = 21.0)

        val gpx = GpxBuilder.build(ride, listOf(point))

        assertThat(gpx).contains("<time>1970-01-02T01:01:01Z</time>")
        // The ride's own start time (metadata/name) is still present alongside it.
        assertThat(gpx).contains("<time>1970-01-01T00:00:00Z</time>")
    }

    @Test
    fun `an empty points list still produces a valid document with no trkpt elements`() {
        val gpx = GpxBuilder.build(ride, emptyList())

        assertThat(gpx).doesNotContain("<trkpt")
        assertThat(gpx).contains("<trkseg>\n    </trkseg>")
    }

    @Test
    fun `multiple points are emitted in order`() {
        val points =
            listOf(
                RideTrackPoint(timestampMs = 1_000L, latitude = 10.0, longitude = 10.0),
                RideTrackPoint(timestampMs = 2_000L, latitude = 20.0, longitude = 20.0),
                RideTrackPoint(timestampMs = 3_000L, latitude = 30.0, longitude = 30.0),
            )

        val gpx = GpxBuilder.build(ride, points)

        val firstIndex = gpx.indexOf("""lat="10.000000"""")
        val secondIndex = gpx.indexOf("""lat="20.000000"""")
        val thirdIndex = gpx.indexOf("""lat="30.000000"""")
        assertThat(firstIndex < secondIndex && secondIndex < thirdIndex).isEqualTo(true)
    }

    @Test
    fun `output always uses a dot decimal separator regardless of the JVM default locale`() {
        Locale.setDefault(Locale.GERMANY)
        val point =
            RideTrackPoint(timestampMs = 90_061_000L, latitude = 52.123456, longitude = 21.654321, altitudeM = 100.5)

        val gpx = GpxBuilder.build(ride, listOf(point))

        assertThat(gpx).contains("""lat="52.123456" lon="21.654321"""")
        assertThat(gpx).contains("<ele>100.5</ele>")
        assertThat(gpx).doesNotContain(",")
    }
}
