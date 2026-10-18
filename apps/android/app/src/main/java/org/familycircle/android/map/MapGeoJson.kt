package org.familycircle.android.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.familycircle.android.ui.family.Freshness
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * GeoJSON for the map's sources, built as plain strings so it can be unit-tested and handed to
 * `GeoJsonSource.setGeoJson(String)`. Property names are shared with the layer definitions.
 */
object MapGeoJson {
    const val PROP_ID = "id"
    const val PROP_ICON = "icon"
    const val PROP_LIVE = "live"
    const val PROP_SELECTED = "selected"
    const val PROP_SORT = "sort"
    const val PROP_COLOR = "color"
    const val PROP_KIND = "kind"

    /** Slate used for outdated positions (same as the web app's `--stale`). */
    const val STALE_COLOR = "#7b8a8f"

    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * One point per marker. The selected marker is drawn on top, then the own marker, then the
     * rest (fresher on top of older).
     */
    fun markers(specs: List<MarkerSpec>, selectedId: String?, iconFor: (MarkerSpec) -> String): String =
        collection(
            specs.map { m ->
                feature(
                    geometry = buildJsonObject {
                        put("type", "Point")
                        put("coordinates", lonLat(m.lon, m.lat))
                    },
                    properties = buildJsonObject {
                        put(PROP_ID, m.userId)
                        put(PROP_ICON, iconFor(m))
                        put(PROP_LIVE, m.freshness == Freshness.LIVE)
                        put(PROP_SELECTED, m.userId == selectedId)
                        put(PROP_SORT, sortKey(m, selectedId))
                        put(PROP_COLOR, colorOf(m))
                    },
                )
            },
        )

    /** Accuracy circles (polygons) for markers with [MarkerSpec.showAccuracyCircle]. */
    fun accuracy(specs: List<MarkerSpec>): String = collection(
        specs.filter { it.showAccuracyCircle }.map { m ->
            feature(
                geometry = buildJsonObject {
                    put("type", "Polygon")
                    put(
                        "coordinates",
                        buildJsonArray {
                            add(JsonArray(circlePolygon(m.lat, m.lon, m.accuracyM).map { (lon, lat) -> lonLat(lon, lat) }))
                        },
                    )
                },
                properties = buildJsonObject {
                    put(PROP_ID, m.userId)
                    put(PROP_COLOR, colorOf(m))
                },
            )
        },
    )

    /** Place circles (polygons of the place radius). */
    fun placeAreas(places: List<PlaceShape>): String = collection(
        places.map { p ->
            feature(
                geometry = polygon(p.lat, p.lon, p.radiusM.toDouble()),
                properties = buildJsonObject { put(PROP_ID, p.id) },
            )
        },
    )

    /** One point per place for its name label (an image, so no map font is needed). */
    fun placeLabels(places: List<PlaceShape>, iconFor: (PlaceShape) -> String): String = collection(
        places.map { p ->
            feature(
                geometry = point(p.lat, p.lon),
                properties = buildJsonObject {
                    put(PROP_ID, p.id)
                    put(PROP_ICON, iconFor(p))
                },
            )
        },
    )

    /** Red SOS pins of other members' active SOS. */
    fun sosPins(pins: List<SosPin>): String = collection(
        pins.map { p ->
            feature(
                geometry = point(p.lat, p.lon),
                properties = buildJsonObject { put(PROP_ID, p.userId) },
            )
        },
    )

    /** The route of a day as one line (points oldest first). Fewer than two points: no line. */
    fun route(points: List<Pair<Double, Double>>): String = collection(
        if (points.size < 2) {
            emptyList()
        } else {
            listOf(
                feature(
                    geometry = buildJsonObject {
                        put("type", "LineString")
                        put("coordinates", JsonArray(points.map { (lat, lon) -> lonLat(lon, lat) }))
                    },
                    properties = buildJsonObject { },
                ),
            )
        },
    )

    /** The single points of a route; [PROP_KIND] is `start`, `end` or `point`. */
    fun routePoints(points: List<Pair<Double, Double>>): String = collection(
        points.mapIndexed { i, (lat, lon) ->
            feature(
                geometry = point(lat, lon),
                properties = buildJsonObject {
                    put(
                        PROP_KIND,
                        when {
                            i == points.lastIndex -> "end"
                            i == 0 -> "start"
                            else -> "point"
                        },
                    )
                },
            )
        },
    )

    private fun point(lat: Double, lon: Double) = buildJsonObject {
        put("type", "Point")
        put("coordinates", lonLat(lon, lat))
    }

    private fun polygon(lat: Double, lon: Double, radiusM: Double) = buildJsonObject {
        put("type", "Polygon")
        put(
            "coordinates",
            buildJsonArray { add(JsonArray(circlePolygon(lat, lon, radiusM).map { (x, y) -> lonLat(x, y) })) },
        )
    }

    fun colorOf(m: MarkerSpec): String = if (m.freshness == Freshness.STALE) STALE_COLOR else m.colorHex

    internal fun sortKey(m: MarkerSpec, selectedId: String?): Double = when {
        m.userId == selectedId -> 1000.0
        m.isSelf -> 500.0
        else -> (3 - m.freshness.ordinal).toDouble() // LIVE 3 > RECENT 2 > STALE 1
    }

    /** Closed ring of `steps + 1` (lon, lat) pairs approximating a circle of [radiusM] metres. */
    fun circlePolygon(lat: Double, lon: Double, radiusM: Double, steps: Int = 48): List<Pair<Double, Double>> {
        val dLat = radiusM / METERS_PER_DEGREE
        val cosLat = cos(lat * PI / 180).coerceAtLeast(0.01)
        val dLon = radiusM / (METERS_PER_DEGREE * cosLat)
        return (0..steps).map { i ->
            val a = if (i == steps) 0.0 else i.toDouble() / steps * 2 * PI
            (lon + dLon * cos(a)) to (lat + dLat * sin(a))
        }
    }

    private fun lonLat(lon: Double, lat: Double) = JsonArray(listOf(JsonPrimitive(lon), JsonPrimitive(lat)))

    private fun feature(geometry: JsonObject, properties: JsonObject) = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", properties)
    }

    private fun collection(features: List<JsonObject>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()
}
