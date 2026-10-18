package org.familycircle.android.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt

/** Visual state of one marker image. [key] identifies the rendered bitmap (used as the image id). */
data class MarkerVisual(
    val key: String,
    val colorHex: String,
    val stale: Boolean,
    val initials: String,
    val avatar: Bitmap?,
    /** Text of the pill above the face (age, "You"), null for none. */
    val pill: String?,
)

/**
 * Draws the marker bitmaps (avatar circle with member colour ring and a pointer tip, like the web
 * app). Outdated (stale) markers are greyscale with a dashed slate ring – never looking live.
 *
 * All geometry is in dp; bitmaps are rendered at the device density and tagged with it, so MapLibre
 * shows them at the right size. The image stands on its anchor (bottom centre = the coordinate).
 */
class MarkerRenderer(private val density: Float, private val densityDpi: Int, fontScale: Float) {
    private val textScale = fontScale.coerceIn(1f, 1.3f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private fun px(dp: Float) = dp * density

    fun render(v: MarkerVisual): Bitmap {
        val pillTextSize = px(PILL_TEXT_DP * textScale)
        text.textSize = pillTextSize
        val pillWidth = v.pill?.let { text.measureText(it) + px(PILL_PAD_DP * 2) } ?: 0f
        val width = maxOf(px(WIDTH_DP), pillWidth + px(4f))
        val height = px(HEIGHT_DP)
        val bmp = createBitmap(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
        bmp.density = densityDpi
        val c = Canvas(bmp)
        val cx = width / 2
        val cy = px(CENTER_Y_DP)
        val ringColor = if (v.stale) STALE else parse(v.colorHex)

        // Pointer tip (drawn first, the face covers its base).
        val tip = Path().apply {
            moveTo(cx - px(8f), cy + px(RING_DP) - px(6f))
            lineTo(cx + px(8f), cy + px(RING_DP) - px(6f))
            lineTo(cx, cy + px(RING_DP + TIP_DP))
            close()
        }
        fill.reset()
        fill.isAntiAlias = true
        fill.color = ringColor
        fill.setShadowLayer(px(3f), 0f, px(1.5f), SHADOW)
        c.drawPath(tip, fill)

        // Ring + white border.
        if (v.stale) {
            fill.color = Color.WHITE
            c.drawCircle(cx, cy, px(RING_DP), fill)
            fill.clearShadowLayer()
            stroke.color = STALE
            stroke.strokeWidth = px(3f)
            stroke.pathEffect = DashPathEffect(floatArrayOf(px(5f), px(3.5f)), 0f)
            c.drawCircle(cx, cy, px(RING_DP - 1.5f), stroke)
        } else {
            c.drawCircle(cx, cy, px(RING_DP), fill)
            fill.clearShadowLayer()
            fill.color = Color.WHITE
            c.drawCircle(cx, cy, px(BORDER_DP), fill)
        }

        // Face: picture or initials.
        val faceR = px(FACE_DP)
        val avatar = v.avatar
        if (avatar != null && !avatar.isRecycled) {
            val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(2 * faceR / avatar.width, 2 * faceR / avatar.height)
            shader.setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(cx - avatar.width * scale / 2, cy - avatar.height * scale / 2)
                },
            )
            fill.reset()
            fill.isAntiAlias = true
            fill.shader = shader
            if (v.stale) fill.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            c.drawCircle(cx, cy, faceR, fill)
            fill.shader = null
            fill.colorFilter = null
            if (v.stale) {
                fill.color = Color.argb(70, 255, 255, 255)
                c.drawCircle(cx, cy, faceR, fill)
            }
        } else {
            fill.reset()
            fill.isAntiAlias = true
            fill.color = ringColor
            c.drawCircle(cx, cy, faceR, fill)
            text.color = Color.WHITE
            text.textSize = px(INITIALS_DP)
            val baseline = cy - (text.descent() + text.ascent()) / 2
            c.drawText(v.initials, cx, baseline, text)
        }

        // Age / "you" pill above the face.
        if (v.pill != null) {
            text.textSize = pillTextSize
            val pillH = px(PILL_H_DP)
            val top = (px(PILL_AREA_DP) - pillH) / 2
            val rect = RectF(cx - pillWidth / 2, top, cx + pillWidth / 2, top + pillH)
            fill.reset()
            fill.isAntiAlias = true
            fill.color = Color.WHITE
            fill.setShadowLayer(px(2f), 0f, px(1f), SHADOW)
            c.drawRoundRect(rect, pillH / 2, pillH / 2, fill)
            fill.clearShadowLayer()
            stroke.pathEffect = null
            stroke.strokeWidth = px(1f)
            stroke.color = if (v.stale) STALE else PILL_BORDER
            c.drawRoundRect(rect, pillH / 2, pillH / 2, stroke)
            text.color = INK
            val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2
            c.drawText(v.pill, cx, baseline, text)
        }
        return bmp
    }

    companion object {
        const val PILL_AREA_DP = 24f
        const val PILL_H_DP = 19f
        const val PILL_PAD_DP = 7f
        const val PILL_TEXT_DP = 11.5f
        const val RING_DP = 27f
        const val BORDER_DP = 24f
        const val FACE_DP = 21f
        const val TIP_DP = 9f
        const val INITIALS_DP = 17f
        const val WIDTH_DP = 64f

        /** Centre of the face, measured from the top of the image. */
        const val CENTER_Y_DP = PILL_AREA_DP + 3f + RING_DP
        const val HEIGHT_DP = CENTER_Y_DP + RING_DP + TIP_DP + 2f

        /** How far the face centre is above the anchor (for the live halo layer). */
        const val FACE_ABOVE_ANCHOR_DP = HEIGHT_DP - CENTER_Y_DP

        private val STALE = MapGeoJson.STALE_COLOR.toColorInt()
        private val SHADOW = Color.argb(70, 0, 0, 0)
        private val INK = "#1b2a2e".toColorInt()
        private val PILL_BORDER = "#d3dcd6".toColorInt()

        private fun parse(hex: String): Int = try {
            hex.toColorInt()
        } catch (_: IllegalArgumentException) {
            MarkerModel.FALLBACK_COLOR.toColorInt()
        }
    }
}
