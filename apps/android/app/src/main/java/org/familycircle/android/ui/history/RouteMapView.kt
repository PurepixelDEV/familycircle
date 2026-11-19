package org.familycircle.android.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.familycircle.android.map.CameraFit
import org.familycircle.android.map.CameraPlan
import org.familycircle.android.map.EdgeInsets
import org.familycircle.android.map.MapGeoJson
import org.familycircle.android.map.MarkerRenderer
import org.familycircle.android.ui.map.MapViewLifecycle
import org.familycircle.android.util.Logger
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre map with the route of one day: the line (start green, end red-brown), stays as dots,
 * the [selected] segment highlighted and the playback position ([playhead]).
 */
@Composable
fun RouteMapView(
    styleUrl: String,
    track: List<TPoint>,
    segments: List<Segment>,
    selected: Segment?,
    playhead: Playhead?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    DisposableEffect(mapView) {
        val forwarder = MapViewLifecycle(mapView)
        lifecycleOwner.lifecycle.addObserver(forwarder)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(forwarder)
            style = null
            map = null
            forwarder.destroy()
        }
    }

    DisposableEffect(mapView, styleUrl) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.setStyle(Style.Builder().fromUri(styleUrl)) { s -> addLayers(s); map = m; style = s }
        }
        onDispose { }
    }

    val pad = (32 * density).toInt()

    fun move(m: MapLibreMap, coords: List<Pair<Double, Double>>) {
        if (coords.isEmpty()) return
        try {
            when (val plan = CameraFit.fitAll(coords, EdgeInsets(pad, pad, pad, pad))) {
                is CameraPlan.Center -> m.animateCamera(
                    CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(plan.lat, plan.lon)).zoom(plan.zoom).build()),
                )
                is CameraPlan.Bounds -> {
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(plan.north, plan.east, plan.south, plan.west), pad, pad, pad, pad))
                    if (m.cameraPosition.zoom > CameraFit.MAX_ZOOM) m.moveCamera(CameraUpdateFactory.zoomTo(CameraFit.MAX_ZOOM))
                }
                CameraPlan.None -> Unit
            }
        } catch (e: RuntimeException) {
            Logger.w("RouteMap", "camera move failed", e)
        }
    }

    // The day: line, start/end, stays – and the camera on the whole day.
    LaunchedEffect(style, track, segments) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        val coords = track.map { it.lat to it.lon }
        s.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(MapGeoJson.route(coords))
        val ends = if (coords.size <= 1) coords else listOf(coords.first(), coords.last())
        s.getSourceAs<GeoJsonSource>(SRC_ENDS)?.setGeoJson(MapGeoJson.routePoints(ends))
        s.getSourceAs<GeoJsonSource>(SRC_STAYS)?.setGeoJson(MapGeoJson.points(segments.filterIsInstance<Stay>().map { it.lat to it.lon }))
        move(m, coords)
    }

    // The selected segment: highlighted, camera on it.
    LaunchedEffect(style, track, selected) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        val part = selected?.let { seg -> track.subList(seg.start.coerceIn(0, track.size), (seg.end + 1).coerceIn(0, track.size)) }.orEmpty()
        val line = if (selected is Stay) emptyList() else part.map { it.lat to it.lon }
        s.getSourceAs<GeoJsonSource>(SRC_SELECTED)?.setGeoJson(MapGeoJson.route(line))
        when (selected) {
            null -> Unit
            is Stay -> move(m, listOf(selected.lat to selected.lon))
            else -> move(m, line)
        }
    }

    LaunchedEffect(style, playhead) {
        val s = style ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_HEAD)?.setGeoJson(MapGeoJson.points(listOfNotNull(playhead?.let { it.lat to it.lon })))
    }

    AndroidView(factory = { mapView }, modifier = modifier.semantics { this.contentDescription = contentDescription })
}

private fun addLayers(s: Style) {
    s.addSource(GeoJsonSource(SRC_LINE, MapGeoJson.route(emptyList())))
    s.addSource(GeoJsonSource(SRC_SELECTED, MapGeoJson.route(emptyList())))
    s.addSource(GeoJsonSource(SRC_ENDS, MapGeoJson.routePoints(emptyList())))
    s.addSource(GeoJsonSource(SRC_STAYS, MapGeoJson.points(emptyList())))
    s.addSource(GeoJsonSource(SRC_HEAD, MapGeoJson.points(emptyList())))
    s.addLayer(
        LineLayer(LAYER_LINE, SRC_LINE).withProperties(
            PropertyFactory.lineColor(MarkerRenderer.PLACE_GREEN_HEX),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    s.addLayer(
        LineLayer(LAYER_SELECTED_CASING, SRC_SELECTED).withProperties(
            PropertyFactory.lineColor("#ffffff"),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    s.addLayer(
        LineLayer(LAYER_SELECTED, SRC_SELECTED).withProperties(
            PropertyFactory.lineColor(HIGHLIGHT_HEX),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    s.addLayer(
        CircleLayer(LAYER_STAYS, SRC_STAYS).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor("#ffffff"),
            PropertyFactory.circleStrokeColor(MarkerRenderer.PLACE_GREEN_HEX),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    s.addLayer(
        CircleLayer(LAYER_ENDS, SRC_ENDS).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get(MapGeoJson.PROP_KIND),
                    Expression.literal(MarkerRenderer.PLACE_GREEN_HEX),
                    Expression.stop("end", "#8a3b12"),
                ),
            ),
            PropertyFactory.circleStrokeColor("#ffffff"),
            PropertyFactory.circleStrokeWidth(1.5f),
        ),
    )
    s.addLayer(
        CircleLayer(LAYER_HEAD, SRC_HEAD).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(HIGHLIGHT_HEX),
            PropertyFactory.circleStrokeColor("#ffffff"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

/** Selected segment and playback position (blue, distinct from the green route). */
private const val HIGHLIGHT_HEX = "#2f6fde"
private const val SRC_LINE = "fc-route"
private const val SRC_SELECTED = "fc-route-selected"
private const val SRC_ENDS = "fc-route-ends"
private const val SRC_STAYS = "fc-route-stays"
private const val SRC_HEAD = "fc-route-head"
private const val LAYER_LINE = "fc-route-line"
private const val LAYER_SELECTED_CASING = "fc-route-selected-casing"
private const val LAYER_SELECTED = "fc-route-selected-line"
private const val LAYER_STAYS = "fc-route-stays-dots"
private const val LAYER_ENDS = "fc-route-ends-dots"
private const val LAYER_HEAD = "fc-route-head-dot"
