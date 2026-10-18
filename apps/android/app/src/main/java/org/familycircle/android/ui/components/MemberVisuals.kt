package org.familycircle.android.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.familycircle.android.R
import org.familycircle.android.map.MapGeoJson
import org.familycircle.android.map.MarkerModel
import org.familycircle.android.ui.family.Freshness
import org.familycircle.android.ui.theme.LocalStatusColors
import org.familycircle.android.util.RelativeAge

val StaleSlate = Color(MapGeoJson.STALE_COLOR.toColorInt())

fun parseMemberColor(hex: String?): Color = try {
    Color(MarkerModel.color(hex).toColorInt())
} catch (_: IllegalArgumentException) {
    Color.Gray
}

/**
 * Freshness as text + colour (never colour only): live = green, recent = amber, outdated = slate
 * with a dashed outline.
 */
@Composable
fun FreshnessBadge(freshness: Freshness, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val text = stringResource(
        when (freshness) {
            Freshness.LIVE -> R.string.freshness_live
            Freshness.RECENT -> R.string.freshness_recent
            Freshness.STALE -> R.string.freshness_stale
        },
    )
    val description = stringResource(R.string.freshness_cd, text)
    val shape = RoundedCornerShape(8.dp)
    val base = modifier.semantics { contentDescription = description }
    when (freshness) {
        Freshness.LIVE -> Text(
            text, color = status.onOk, style = MaterialTheme.typography.labelLarge,
            modifier = base.background(status.ok, shape).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Freshness.RECENT -> Text(
            text, color = status.onWarning, style = MaterialTheme.typography.labelLarge,
            modifier = base.background(status.warning, shape).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Freshness.STALE -> {
            val outline = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = base
                    .drawBehind {
                        drawRoundRect(
                            color = outline,
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** Round avatar with the member colour ring (decorative: the name is always shown next to it). */
@Composable
fun MemberAvatar(
    name: String,
    colorHex: String,
    avatar: Bitmap?,
    stale: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val ring = if (stale) StaleSlate else parseMemberColor(colorHex)
    val greyscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        if (stale) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = ring,
                    radius = this.size.minDimension / 2 - 1.5.dp.toPx(),
                    style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
                )
            }
        }
        val inner = Modifier
            .size(size)
            .let { if (stale) it else it.border(3.dp, ring, CircleShape) }
            .padding(5.dp)
            .clip(CircleShape)
        if (avatar != null) {
            Image(
                bitmap = remember(avatar) { avatar.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (stale) greyscale else null,
                modifier = inner,
            )
        } else {
            Box(inner.background(ring), contentAlignment = Alignment.Center) {
                Text(
                    MarkerModel.initials(name),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.34f).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun motionText(motion: String): String? = when (motion) {
    "stationary" -> stringResource(R.string.motion_stationary)
    "walking" -> stringResource(R.string.motion_walking)
    "driving" -> stringResource(R.string.motion_driving)
    else -> null
}

/** Compact age for the map marker pill ("5 min", "2 h"). */
@Composable
fun shortAgeText(thenMs: Long, nowMs: Long): String = when (val age = RelativeAge.of(thenMs, nowMs)) {
    RelativeAge.JustNow -> stringResource(R.string.age_short_now)
    is RelativeAge.Minutes -> stringResource(R.string.age_short_minutes, age.value)
    is RelativeAge.Hours -> stringResource(R.string.age_short_hours, age.value)
    is RelativeAge.Days -> stringResource(R.string.age_short_days, age.value)
}
