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

/** MapLibre map with the route of one day (line + points; start green, end red-brown). */
@Composable
fun RouteMapView(styleUrl: String, track: List<TrackPoint>, contentDescription: String, modifier: Modifier = Modifier) {
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
            m.setStyle(Style.Builder().fromUri(styleUrl)) { s ->
                s.addSource(GeoJsonSource(SRC_LINE, MapGeoJson.route(emptyList())))
                s.addSource(GeoJsonSource(SRC_POINTS, MapGeoJson.routePoints(emptyList())))
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
                    CircleLayer(LAYER_POINTS, SRC_POINTS).withProperties(
                        PropertyFactory.circleRadius(
                            Expression.switchCase(
                                Expression.eq(Expression.get(MapGeoJson.PROP_KIND), Expression.literal("point")),
                                Expression.literal(3.5f),
                                Expression.literal(7f),
                            ),
                        ),
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
                map = m
                style = s
            }
        }
        onDispose { }
    }

    LaunchedEffect(style, track) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        val coords = track.map { it.lat to it.lon }
        s.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(MapGeoJson.route(coords))
        s.getSourceAs<GeoJsonSource>(SRC_POINTS)?.setGeoJson(MapGeoJson.routePoints(coords))
        if (coords.isEmpty()) return@LaunchedEffect
        val pad = (32 * density).toInt()
        val plan = CameraFit.fitAll(coords, EdgeInsets(pad, pad, pad, pad))
        try {
            when (plan) {
                is CameraPlan.Center -> m.moveCamera(
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

    AndroidView(factory = { mapView }, modifier = modifier.semantics { this.contentDescription = contentDescription })
}

private const val SRC_LINE = "fc-route"
private const val SRC_POINTS = "fc-route-points"
private const val LAYER_LINE = "fc-route-line"
private const val LAYER_POINTS = "fc-route-dots"
