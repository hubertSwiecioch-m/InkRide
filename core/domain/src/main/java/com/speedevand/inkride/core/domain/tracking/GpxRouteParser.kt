package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class GpxParseError : Error {
    /** The file parsed but contained no usable track or route points. */
    EMPTY,

    /** The bytes were not well-formed GPX/XML. */
    MALFORMED
}

/**
 * Parses a GPX 1.1 document into a [PlannedRoute].
 *
 * Pure (uses only the JVM/Android `javax.xml` DOM parser, no Android types) so it
 * lives in the domain module and is unit-tested directly. The follow-the-route
 * polyline is taken from track points (`<trkpt>`); if a file has none it falls
 * back to route points (`<rtept>`). Turn/POI markers come from `<wpt>` plus any
 * named `<rtept>`, matching how routing tools emit turn-by-turn GPX.
 */
object GpxRouteParser {

    fun parse(xml: String): Result<PlannedRoute, GpxParseError> {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // Harden against XXE — these files come from arbitrary storage.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return Result.Error(GpxParseError.MALFORMED)
        }

        val trackPoints = doc.elements("trkpt").mapNotNull { it.toRoutePoint() }
        val rtePtElements = doc.elements("rtept")
        val routePoints = rtePtElements.mapNotNull { it.toRoutePoint() }

        val points = if (trackPoints.isNotEmpty()) trackPoints else routePoints
        if (points.isEmpty()) return Result.Error(GpxParseError.EMPTY)

        val waypoints = buildList {
            doc.elements("wpt").forEach { el -> el.toWaypoint()?.let { add(it) } }
            // Named route points double as turn markers in turn-by-turn GPX.
            rtePtElements.forEach { el ->
                val name = el.childText("name")
                if (!name.isNullOrBlank()) el.toWaypoint()?.let { add(it) }
            }
        }

        return Result.Success(
            PlannedRoute(
                name = doc.routeName(),
                points = points,
                waypoints = waypoints
            )
        )
    }

    private fun Element.toRoutePoint(): RoutePoint? {
        val lat = getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = getAttribute("lon").toDoubleOrNull() ?: return null
        return RoutePoint(lat, lon)
    }

    private fun Element.toWaypoint(): RouteWaypoint? {
        val lat = getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = getAttribute("lon").toDoubleOrNull() ?: return null
        return RouteWaypoint(lat, lon, childText("name")?.takeIf { it.isNotBlank() })
    }

    // ── DOM helpers ─────────────────────────────────────────────────────────

    private fun org.w3c.dom.Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** Route name from the `<trk>`, `<rte>`, or `<metadata>` block (not a waypoint). */
    private fun org.w3c.dom.Document.routeName(): String? {
        for (parent in listOf("trk", "rte", "metadata")) {
            val name = elements(parent).firstNotNullOfOrNull { el ->
                el.childText("name")?.takeIf { it.isNotBlank() }
            }
            if (name != null) return name
        }
        return null
    }

    /** Direct-child text of [tag], or null if absent. */
    private fun Element.childText(tag: String): String? {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tag) {
                return node.textContent?.trim()
            }
        }
        return null
    }
}
