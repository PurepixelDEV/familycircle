package org.familycircle.android.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Padding in pixels (left, top, right, bottom). */
data class EdgeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Where to move the camera. */
sealed interface CameraPlan {
    data object None : CameraPlan

    data class Center(val lat: Double, val lon: Double, val zoom: Double, val padding: EdgeInsets) : CameraPlan

    data class Bounds(
        val north: Double,
        val east: Double,
        val south: Double,
        val west: Double,
        val padding: EdgeInsets,
    ) : CameraPlan
}

/**
 * Pure helpers for "show everyone" and "focus member" that keep markers clear of the floating UI:
 * the member sheet (bottom on phones) or side panel, the status chip on top, and the marker's own
 * height above its anchor point (the marker image stands on the coordinate).
 */
object CameraFit {
    const val SINGLE_ZOOM = 15.0
    const val MAX_ZOOM = 16.0

    /** Marker image height above the anchor (dp) incl. the age pill. */
    const val MARKER_HEIGHT_DP = 84f

    /** Points closer than this (degrees, ~20 m) are treated as one point. */
    private const val DEGENERATE_SPAN = 0.0002

    /**
     * @param viewWidthPx / viewHeightPx size of the map view.
     * @param coveredBottomPx height of the map hidden by the bottom sheet (0 if none).
     * @param coveredLeftPx width hidden by a panel drawn over the map (0 for a side-by-side panel).
     * @param topOverlayPx height of the chips at the top of the map.
     */
    fun padding(
        viewWidthPx: Int,
        viewHeightPx: Int,
        coveredBottomPx: Int,
        coveredLeftPx: Int,
        topOverlayPx: Int,
        density: Float,
    ): EdgeInsets {
        fun dp(v: Float) = (v * density).toInt()
        val minVisible = dp(48f)
        var left = coveredLeftPx.coerceAtLeast(0) + dp(40f)
        var right = dp(40f)
        var top = topOverlayPx.coerceAtLeast(0) + dp(MARKER_HEIGHT_DP) + dp(8f)
        var bottom = coveredBottomPx.coerceAtLeast(0) + dp(24f)

        // Never leave less than [minVisible] px for the content (MapLibre rejects impossible
        // padding); shrink proportionally when the sheet covers most of a small/landscape screen.
        val maxH = (viewWidthPx - minVisible).coerceAtLeast(0)
        if (left + right > maxH && left + right > 0) {
            val f = maxH.toFloat() / (left + right)
            left = (left * f).toInt()
            right = (right * f).toInt()
        }
        val maxV = (viewHeightPx - minVisible).coerceAtLeast(0)
        if (top + bottom > maxV && top + bottom > 0) {
            val f = maxV.toFloat() / (top + bottom)
            top = (top * f).toInt()
            bottom = (bottom * f).toInt()
        }
        return EdgeInsets(left, top, right, bottom)
    }

    /** Camera for showing all [points] (lat, lon). */
    fun fitAll(points: List<Pair<Double, Double>>, padding: EdgeInsets): CameraPlan {
        if (points.isEmpty()) return CameraPlan.None
        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        for ((lat, lon) in points) {
            north = max(north, lat)
            south = min(south, lat)
            east = max(east, lon)
            west = min(west, lon)
        }
        if (abs(north - south) < DEGENERATE_SPAN && abs(east - west) < DEGENERATE_SPAN) {
            return CameraPlan.Center((north + south) / 2, (east + west) / 2, SINGLE_ZOOM, padding)
        }
        return CameraPlan.Bounds(north, east, south, west, padding)
    }

    /** Camera for one member: keep the current zoom if it is already closer. */
    fun focus(lat: Double, lon: Double, currentZoom: Double, padding: EdgeInsets): CameraPlan =
        CameraPlan.Center(lat, lon, max(currentZoom, SINGLE_ZOOM), padding)
}
