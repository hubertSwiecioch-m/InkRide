package com.speedevand.inkride.core.domain.history

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a standard GPX 1.1 document from a ride and its track points.
 *
 * Pure (no Android dependencies) so it can live in the domain module and be unit
 * tested directly. All numbers are formatted with [Locale.US] so the decimal
 * separator is always '.', and timestamps are emitted as UTC ISO-8601 — both
 * required by the GPX schema and by importers like Strava/Komoot/OsmAnd.
 */
object GpxBuilder {
    private const val GPX_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    fun build(
        ride: RideRecord,
        points: List<RideTrackPoint>,
    ): String {
        val timeFormat =
            SimpleDateFormat(GPX_TIME_FORMAT, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        val startTime = timeFormat.format(Date(ride.startTimestamp))

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<gpx version="1.1" creator="InkRide" """)
            append("""xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
            append("  <metadata>\n")
            append("    <time>").append(startTime).append("</time>\n")
            append("  </metadata>\n")
            append("  <trk>\n")
            append("    <name>InkRide ").append(startTime).append("</name>\n")
            append("    <trkseg>\n")
            points.forEach { point ->
                append("      <trkpt lat=\"").append(coord(point.latitude))
                append("\" lon=\"").append(coord(point.longitude)).append("\">")
                point.altitudeM?.let { append("<ele>").append(elevation(it)).append("</ele>") }
                append("<time>").append(timeFormat.format(Date(point.timestampMs))).append("</time>")
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
            append("  </trk>\n")
            append("</gpx>\n")
        }
    }

    // ~0.1 m resolution — plenty for cycling and the GPX de-facto convention.
    private fun coord(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun elevation(value: Double): String = String.format(Locale.US, "%.1f", value)
}
