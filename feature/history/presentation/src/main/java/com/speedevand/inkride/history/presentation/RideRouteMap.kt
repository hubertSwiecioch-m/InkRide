package com.speedevand.inkride.history.presentation

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Plots a recorded ride track as a polyline on an OpenStreetMap [MapView].
 *
 * E-Ink tuned: tiles are rendered grayscale (saturation 0) for sharp monochrome
 * contrast, the route is a thick solid-black line, inertial fling is disabled
 * (no fluid scrolling), and zoom buttons stay permanently visible so zooming is
 * a discrete tap rather than a pinch animation. Tiles are fetched from OSM and
 * cached in app-scoped storage ([android.content.Context.getCacheDir]).
 */
@Composable
fun RideRouteMap(
    points: List<TrackPointUi>,
    modifier: Modifier = Modifier,
) {
    val geoPoints = remember(points) { points.map { GeoPoint(it.lat, it.lng) } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // OSM tile servers reject blank user-agents; cache under app storage.
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                osmdroidTileCache = File(osmdroidBasePath, "tiles")
            }

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                isFlingEnabled = false
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)

                // OsmDroid's MapView never calls requestDisallowInterceptTouchEvent,
                // so a parent verticalScroll would steal vertical pan gestures and
                // lock the map. Claim the gesture for the map while a touch is down;
                // returning false leaves OsmDroid's own onTouchEvent handling intact.
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false
                }

                // Grayscale tiles for E-Ink legibility.
                overlayManager.tilesOverlay.setColorFilter(
                    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }),
                )

                val route =
                    Polyline(this).apply {
                        setPoints(geoPoints)
                        outlinePaint.color = Color.BLACK
                        outlinePaint.strokeWidth = 8f
                    }
                overlays.add(route)

                // Frame the whole track once the view has been measured.
                addOnFirstLayoutListener { _, _, _, _, _ ->
                    fitToTrack(geoPoints)
                }
            }
        },
        update = { mapView ->
            val route = mapView.overlays.filterIsInstance<Polyline>().firstOrNull()
            route?.setPoints(geoPoints)
            mapView.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}

private fun MapView.fitToTrack(geoPoints: List<GeoPoint>) {
    if (geoPoints.isEmpty()) return
    if (geoPoints.size == 1) {
        controller.setZoom(16.0)
        controller.setCenter(geoPoints.first())
        return
    }
    val box = BoundingBox.fromGeoPoints(geoPoints)
    // A near-stationary ride collapses the box to zero span; zoomToBoundingBox
    // can't resolve a zoom for that, so center it at a fixed level instead.
    if (box.latitudeSpan <= 0.0 || box.longitudeSpanWithDateLine <= 0.0) {
        controller.setZoom(16.0)
        controller.setCenter(box.centerWithDateLine)
        return
    }
    zoomToBoundingBox(box, false, MAP_EDGE_PADDING_PX)
}

private const val MAP_EDGE_PADDING_PX = 48
