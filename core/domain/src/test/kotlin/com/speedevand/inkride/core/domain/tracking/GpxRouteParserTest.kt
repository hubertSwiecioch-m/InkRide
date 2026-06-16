package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.Result
import org.junit.jupiter.api.Test

class GpxRouteParserTest {

    @Test
    fun `parses track points into the follow polyline`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <name>Morning Loop</name>
                <trkseg>
                  <trkpt lat="52.0" lon="21.0"><ele>100</ele></trkpt>
                  <trkpt lat="52.001" lon="21.001"/>
                  <trkpt lat="52.002" lon="21.002"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val route = (GpxRouteParser.parse(gpx) as Result.Success).data

        assertThat(route.name).isEqualTo("Morning Loop")
        assertThat(route.points).hasSize(3)
        assertThat(route.points.first()).isEqualTo(RoutePoint(52.0, 21.0))
    }

    @Test
    fun `falls back to route points when no track exists`() {
        val gpx = """
            <gpx version="1.1">
              <rte>
                <name>Planned</name>
                <rtept lat="52.0" lon="21.0"/>
                <rtept lat="52.01" lon="21.01"><name>Turn left</name></rtept>
              </rte>
            </gpx>
        """.trimIndent()

        val route = (GpxRouteParser.parse(gpx) as Result.Success).data

        assertThat(route.points).hasSize(2)
        // The named route point doubles as a turn marker.
        assertThat(route.waypoints).hasSize(1)
        assertThat(route.waypoints.first().name).isEqualTo("Turn left")
    }

    @Test
    fun `collects standalone waypoints`() {
        val gpx = """
            <gpx version="1.1">
              <wpt lat="52.0" lon="21.0"><name>Start</name></wpt>
              <trk><trkseg>
                <trkpt lat="52.0" lon="21.0"/>
                <trkpt lat="52.01" lon="21.01"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val route = (GpxRouteParser.parse(gpx) as Result.Success).data

        assertThat(route.waypoints).hasSize(1)
        assertThat(route.waypoints.first()).isEqualTo(RouteWaypoint(52.0, 21.0, "Start"))
    }

    @Test
    fun `empty file is rejected`() {
        val gpx = """<gpx version="1.1"></gpx>"""

        val result = GpxRouteParser.parse(gpx)

        assertThat((result as Result.Error).error).isEqualTo(GpxParseError.EMPTY)
    }

    @Test
    fun `malformed xml is rejected`() {
        val result = GpxRouteParser.parse("not xml at all <<<")

        assertThat((result as Result.Error).error).isEqualTo(GpxParseError.MALFORMED)
    }
}
