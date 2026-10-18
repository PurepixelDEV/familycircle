package org.familycircle.android.ui.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.provider.Settings
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.familycircle.android.map.CameraFit
import org.familycircle.android.map.CameraPlan
import org.familycircle.android.map.EdgeInsets
import org.familycircle.android.map.MapGeoJson
import org.familycircle.android.map.MarkerRenderer
import org.familycircle.android.map.MarkerSpec
import org.familycircle.android.map.MarkerVisual
import org.familycircle.android.map.PlaceShape
import org.familycircle.android.map.SosPin
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** One marker to draw: data + its rendered look. */
data class MapMarker(val spec: MarkerSpec, val visual: MarkerVisual)

/**
 * A camera move requested by the UI; a new [nonce] triggers it again. With [lat]/[lon] the camera
 * centres on that coordinate (e.g. an SOS position), otherwise on [focusUserId] or on everyone.
 */
data class CameraRequest(val nonce: Int, val focusUserId: String?, val lat: Double? = null, val lon: Double? = null)

/**
 * MapLibre map with the family's markers, accuracy circles and a subtle pulse for live positions.
 *
 * The [MapView] gets every lifecycle callback exactly once and in order (onStart/onResume/onPause/
 * onStop/onDestroy) from the hosting [LifecycleOwner], plus onLowMemory; it is destroyed when this
 * composable leaves the composition, so no GL surface or listener outlives the screen.
 *
 * @param paddingProvider current insets for camera moves (sheet/panel/top chips); read lazily so
 *        the bottom sheet position at the moment of the move is used.
 */
@Composable
fun FamilyMapView(
    styleUrl: String,
    markers: List<MapMarker>,
    selectedId: String?,
    cameraRequest: CameraRequest?,
    paddingProvider: () -> EdgeInsets,
    contentDescription: String,
    onMarkerClick: (String) -> Unit,
    onStyleFailed: () -> Unit,
    modifier: Modifier = Modifier,
    places: List<PlaceShape> = emptyList(),
    sosPins: List<SosPin> = emptyList(),
    onSosClick: (String) -> Unit = {},
    onLongPress: ((lat: Double, lon: Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val currentOnClick by rememberUpdatedState(onMarkerClick)
    val currentOnFailed by rememberUpdatedState(onStyleFailed)
    val currentPadding by rememberUpdatedState(paddingProvider)
    val currentOnSos by rememberUpdatedState(onSosClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    val controller = remember {
        MapController(
            MarkerRenderer(density.density, configuration.densityDpi, density.fontScale),
            density.density,
        )
    }
    var styleReady by remember { mutableStateOf(false) }

    // Lifecycle forwarding + low-memory callbacks; destroys the MapView on dispose.
    DisposableEffect(mapView) {
        val lifecycle = lifecycleOwner.lifecycle
        val forwarder = MapViewLifecycle(mapView)
        lifecycle.addObserver(forwarder)
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = forwarder.lowMemory()

            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) forwarder.lowMemory()
            }
        }
        context.registerComponentCallbacks(memory)
        onDispose {
            context.unregisterComponentCallbacks(memory)
            lifecycle.removeObserver(forwarder)
            controller.detach()
            forwarder.destroy()
        }
    }

    // Load the style once per URL.
    DisposableEffect(mapView, styleUrl) {
        styleReady = false
        val failListener = MapView.OnDidFailLoadingMapListener { message ->
            Logger.w(TAG, "map style failed to load")
            Logger.d(TAG) { "style error: $message" }
            currentOnFailed()
        }
        mapView.addOnDidFailLoadingMapListener(failListener)
        mapView.getMapAsync { map ->
            controller.configure(map)
            map.addOnMapClickListener { point ->
                val sos = controller.sosAt(map, point)
                if (sos != null) {
                    currentOnSos(sos)
                    return@addOnMapClickListener true
                }
                val id = controller.markerAt(map, point)
                if (id != null) currentOnClick(id)
                id != null
            }
            map.addOnMapLongClickListener { point ->
                val handler = currentOnLongPress ?: return@addOnMapLongClickListener false
                handler(point.latitude, point.longitude)
                true
            }
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                controller.onStyleLoaded(map, style)
                styleReady = true
            }
        }
        onDispose { mapView.removeOnDidFailLoadingMapListener(failListener) }
    }

    LaunchedEffect(styleReady, markers, selectedId) {
        if (!styleReady) return@LaunchedEffect
        controller.update(markers, selectedId)
        // First data: show everyone without animation.
        if (!controller.fittedOnce && markers.isNotEmpty()) {
            controller.fittedOnce = true
            controller.move(CameraFit.fitAll(markers.map { it.spec.lat to it.spec.lon }, currentPadding()), animate = false)
        }
    }

    LaunchedEffect(styleReady, places) {
        if (styleReady) controller.updatePlaces(places)
    }

    LaunchedEffect(styleReady, sosPins) {
        if (styleReady) controller.updateSos(sosPins)
    }

    LaunchedEffect(styleReady, cameraRequest?.nonce) {
        val request = cameraRequest ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val padding = currentPadding()
        val plan = if (request.lat != null && request.lon != null) {
            CameraFit.focus(request.lat, request.lon, maxOf(controller.currentZoom(), CameraFit.SINGLE_ZOOM), padding)
        } else if (request.focusUserId != null) {
            val m = markers.firstOrNull { it.spec.userId == request.focusUserId } ?: return@LaunchedEffect
            CameraFit.focus(m.spec.lat, m.spec.lon, controller.currentZoom(), padding)
        } else {
            CameraFit.fitAll(markers.map { it.spec.lat to it.spec.lon }, padding)
        }
        controller.fittedOnce = true
        controller.move(plan, animate = true)
    }

    // Subtle pulse for live markers, only while resumed; static halo when animations are off.
    val anyLive = markers.any { it.spec.freshness == org.familycircle.android.ui.family.Freshness.LIVE }
    LaunchedEffect(styleReady, anyLive, lifecycleOwner) {
        if (!styleReady || !anyLive) return@LaunchedEffect
        val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reduceMotion) {
            controller.setHalo(scale = 1.15f, opacity = 0.4f)
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = System.nanoTime()
            while (true) {
                val t = (((System.nanoTime() - start) / 1_000_000L) % PULSE_MS).toFloat() / PULSE_MS
                controller.setHalo(scale = 0.85f + 0.5f * t, opacity = 0.7f * (1f - t))
                delay(PULSE_FRAME_MS)
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    )
}

private const val TAG = "Map"
private const val PULSE_MS = 2600L
private const val PULSE_FRAME_MS = 66L

/** Forwards lifecycle events to the [MapView] exactly once each, in order. */
internal class MapViewLifecycle(private val view: MapView) : LifecycleEventObserver {
    private var started = false
    private var resumed = false
    private var destroyed = false

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    private fun start() {
        if (destroyed || started) return
        view.onStart()
        started = true
    }

    private fun resume() {
        if (destroyed) return
        start()
        if (!resumed) {
            view.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed) {
            view.onPause()
            resumed = false
        }
    }

    private fun stop() {
        pause()
        if (started) {
            view.onStop()
            started = false
        }
    }

    fun destroy() {
        if (destroyed) return
        stop()
        view.onDestroy()
        destroyed = true
    }

    fun lowMemory() {
        if (!destroyed) view.onLowMemory()
    }
}

/** Owns sources, layers and marker images of one map. Main thread only. */
private class MapController(private val renderer: MarkerRenderer, private val density: Float) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val images = HashSet<String>()
    private val placeImages = HashSet<String>()
    private var sosImage = false
    var fittedOnce = false

    fun configure(map: MapLibreMap) {
        this.map = map
        map.uiSettings.apply {
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = false
            // Keep the map attribution visible above the bottom sheet: top end, below the SOS button
            // (64 dp + 12 dp padding) of the map screen.
            attributionGravity = Gravity.TOP or Gravity.END
            logoGravity = Gravity.TOP or Gravity.END
            val m = (8 * density).toInt()
            val top = (ATTRIBUTION_TOP_DP * density).toInt()
            setAttributionMargins(0, top, m, 0)
            setLogoMargins(0, top, (40 * density).toInt(), 0)
        }
        map.setMaxZoomPreference(19.0)
    }

    fun onStyleLoaded(map: MapLibreMap, style: Style) {
        this.map = map
        this.style = style
        images.clear()
        placeImages.clear()
        sosImage = false
        // Places first (below everything), then accuracy circles, labels, markers, SOS pins on top.
        style.addSource(GeoJsonSource(SRC_PLACES, MapGeoJson.placeAreas(emptyList())))
        style.addSource(GeoJsonSource(SRC_PLACE_LABELS, MapGeoJson.placeLabels(emptyList()) { "" }))
        style.addSource(GeoJsonSource(SRC_ACCURACY, MapGeoJson.accuracy(emptyList())))
        style.addSource(GeoJsonSource(SRC_MARKERS, MapGeoJson.markers(emptyList(), null) { "" }))
        style.addSource(GeoJsonSource(SRC_SOS, MapGeoJson.sosPins(emptyList())))
        style.addLayer(
            FillLayer(LAYER_PLACE_FILL, SRC_PLACES).withProperties(
                PropertyFactory.fillColor(MarkerRenderer.PLACE_GREEN_HEX),
                PropertyFactory.fillOpacity(0.10f),
            ),
        )
        style.addLayer(
            LineLayer(LAYER_PLACE_LINE, SRC_PLACES).withProperties(
                PropertyFactory.lineColor(MarkerRenderer.PLACE_GREEN_HEX),
                PropertyFactory.lineOpacity(0.7f),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_PLACE_LABELS, SRC_PLACE_LABELS).withProperties(
                PropertyFactory.iconImage(Expression.get(MapGeoJson.PROP_ICON)),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.iconOptional(false),
            ),
        )
        style.addLayer(
            FillLayer(LAYER_ACC_FILL, SRC_ACCURACY).withProperties(
                PropertyFactory.fillColor(Expression.toColor(Expression.get(MapGeoJson.PROP_COLOR))),
                PropertyFactory.fillOpacity(0.12f),
            ),
        )
        style.addLayer(
            LineLayer(LAYER_ACC_LINE, SRC_ACCURACY).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get(MapGeoJson.PROP_COLOR))),
                PropertyFactory.lineOpacity(0.45f),
                PropertyFactory.lineWidth(1.5f),
            ),
        )
        style.addLayer(
            CircleLayer(LAYER_HALO, SRC_MARKERS).withProperties(
                PropertyFactory.circleRadius(MarkerRenderer.RING_DP),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get(MapGeoJson.PROP_COLOR))),
                PropertyFactory.circleStrokeOpacity(0f),
                PropertyFactory.circleTranslate(arrayOf(0f, -MarkerRenderer.FACE_ABOVE_ANCHOR_DP)),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT),
            ).withFilter(Expression.eq(Expression.get(MapGeoJson.PROP_LIVE), Expression.literal(true))),
        )
        style.addLayer(
            SymbolLayer(LAYER_MARKERS, SRC_MARKERS).withProperties(
                PropertyFactory.iconImage(Expression.get(MapGeoJson.PROP_ICON)),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(Expression.get(MapGeoJson.PROP_SORT)),
                PropertyFactory.iconSize(
                    Expression.switchCase(
                        Expression.eq(Expression.get(MapGeoJson.PROP_SELECTED), Expression.literal(true)),
                        Expression.literal(1.12f),
                        Expression.literal(1f),
                    ),
                ),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_SOS, SRC_SOS).withProperties(
                PropertyFactory.iconImage(IMAGE_SOS),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    fun updatePlaces(places: List<PlaceShape>) {
        val style = style ?: return
        if (!style.isFullyLoaded) return
        fun key(p: PlaceShape) = "place|${p.name}"
        val needed = places.map(::key).toSet()
        for (p in places) {
            val k = key(p)
            if (k !in placeImages) {
                style.addImage(k, renderer.renderLabel(p.name))
                placeImages += k
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_PLACES)?.setGeoJson(MapGeoJson.placeAreas(places))
        style.getSourceAs<GeoJsonSource>(SRC_PLACE_LABELS)?.setGeoJson(MapGeoJson.placeLabels(places, ::key))
        val obsolete = placeImages - needed
        obsolete.forEach { style.removeImage(it) }
        placeImages -= obsolete
    }

    fun updateSos(pins: List<SosPin>) {
        val style = style ?: return
        if (!style.isFullyLoaded) return
        if (!sosImage && pins.isNotEmpty()) {
            style.addImage(IMAGE_SOS, renderer.renderSos())
            sosImage = true
        }
        style.getSourceAs<GeoJsonSource>(SRC_SOS)?.setGeoJson(MapGeoJson.sosPins(pins))
    }

    /** User id of the SOS pin at [point], if any. */
    fun sosAt(map: MapLibreMap, point: LatLng): String? {
        if (!sosImage) return null
        val screen = map.projection.toScreenLocation(point)
        val slop = 8 * density
        val rect = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
        return map.queryRenderedFeatures(rect, LAYER_SOS).firstNotNullOfOrNull { it.getStringProperty(MapGeoJson.PROP_ID) }
    }

    fun update(markers: List<MapMarker>, selectedId: String?) {
        val style = style ?: return
        if (!style.isFullyLoaded) return
        val needed = markers.map { it.visual.key }.toSet()
        for (m in markers) {
            if (m.visual.key !in images) {
                style.addImage(m.visual.key, renderer.render(m.visual))
                images += m.visual.key
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_MARKERS)
            ?.setGeoJson(MapGeoJson.markers(markers.map { it.spec }, selectedId) { spec -> markers.first { it.spec === spec }.visual.key })
        style.getSourceAs<GeoJsonSource>(SRC_ACCURACY)?.setGeoJson(MapGeoJson.accuracy(markers.map { it.spec }))
        // Drop images no marker uses any more (after the source no longer references them).
        val obsolete = images - needed
        obsolete.forEach { style.removeImage(it) }
        images -= obsolete
    }

    fun setHalo(scale: Float, opacity: Float) {
        val style = style ?: return
        if (!style.isFullyLoaded) return
        style.getLayerAs<CircleLayer>(LAYER_HALO)?.setProperties(
            PropertyFactory.circleRadius(MarkerRenderer.RING_DP * scale),
            PropertyFactory.circleStrokeOpacity(opacity),
        )
    }

    fun currentZoom(): Double = map?.cameraPosition?.zoom ?: CameraFit.SINGLE_ZOOM

    fun move(plan: CameraPlan, animate: Boolean) {
        val map = map ?: return
        val update = when (plan) {
            CameraPlan.None -> return
            is CameraPlan.Center -> CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(plan.lat, plan.lon))
                    .zoom(plan.zoom)
                    .padding(
                        plan.padding.left.toDouble(),
                        plan.padding.top.toDouble(),
                        plan.padding.right.toDouble(),
                        plan.padding.bottom.toDouble(),
                    )
                    .build(),
            )
            is CameraPlan.Bounds -> CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(plan.north, plan.east, plan.south, plan.west),
                plan.padding.left,
                plan.padding.top,
                plan.padding.right,
                plan.padding.bottom,
            )
        }
        try {
            map.cancelTransitions()
            if (animate) map.animateCamera(update, CAMERA_MS) else map.moveCamera(update)
            if (plan is CameraPlan.Bounds && map.cameraPosition.zoom > CameraFit.MAX_ZOOM) {
                map.moveCamera(CameraUpdateFactory.zoomTo(CameraFit.MAX_ZOOM))
            }
        } catch (e: RuntimeException) {
            // E.g. padding larger than the view during a configuration change – the next request fixes it.
            Logger.w(TAG, "camera move failed", e)
        }
    }

    fun markerAt(map: MapLibreMap, point: LatLng): String? {
        val screen = map.projection.toScreenLocation(point)
        val slop = 6 * density
        val rect = RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
        return map.queryRenderedFeatures(rect, LAYER_MARKERS)
            .firstNotNullOfOrNull { f -> f.getStringProperty(MapGeoJson.PROP_ID) }
    }

    fun detach() {
        map = null
        style = null
        images.clear()
        placeImages.clear()
        sosImage = false
    }

    companion object {
        const val SRC_MARKERS = "fc-markers"
        const val SRC_ACCURACY = "fc-accuracy"
        const val LAYER_ACC_FILL = "fc-accuracy-fill"
        const val LAYER_ACC_LINE = "fc-accuracy-line"
        const val LAYER_HALO = "fc-halo"
        const val LAYER_MARKERS = "fc-marker-icons"
        const val SRC_PLACES = "fc-places"
        const val SRC_PLACE_LABELS = "fc-place-labels"
        const val SRC_SOS = "fc-sos"
        const val LAYER_PLACE_FILL = "fc-place-fill"
        const val LAYER_PLACE_LINE = "fc-place-line"
        const val LAYER_PLACE_LABELS = "fc-place-labels"
        const val LAYER_SOS = "fc-sos-pins"
        const val IMAGE_SOS = "fc-sos-pin"
        const val CAMERA_MS = 600
        const val ATTRIBUTION_TOP_DP = 84f
    }
}

/** Helper for other files (e.g. the screen) to obtain a [Context]-independent check. */
internal fun Context.animationsDisabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
