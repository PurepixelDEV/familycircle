package org.familycircle.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6B52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2D2),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6359),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE9DB),
    onSecondaryContainer = Color(0xFF092017),
    tertiary = Color(0xFF3E6374),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC2E8FC),
    onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFFBFDFB),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDFB),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF404944),
    outline = Color(0xFF707974),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD6B7),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFFA6F2D2),
    secondary = Color(0xFFB3CCBF),
    onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF354B41),
    onSecondaryContainer = Color(0xFFCEE9DB),
    tertiary = Color(0xFFA6CCE0),
    onTertiary = Color(0xFF083544),
    tertiaryContainer = Color(0xFF254B5B),
    onTertiaryContainer = Color(0xFFC2E8FC),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E3E0),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE0E3E0),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF8A938E),
)

/** Semantic status colours that are not part of the Material scheme. Always paired with text. */
@Immutable
data class StatusColors(
    val ok: Color,
    val onOk: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val neutral: Color,
    val onNeutral: Color,
)

private val LightStatus = StatusColors(
    ok = Color(0xFF1E7B3A), onOk = Color.White,
    warning = Color(0xFF8A5A00), onWarning = Color.White,
    warningContainer = Color(0xFFFFE0B2), onWarningContainer = Color(0xFF2B1700),
    neutral = Color(0xFF5C6360), onNeutral = Color.White,
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF7FD891), onOk = Color(0xFF00391A),
    warning = Color(0xFFFFB951), onWarning = Color(0xFF462A00),
    warningContainer = Color(0xFF5E3F00), onWarningContainer = Color(0xFFFFDDB1),
    neutral = Color(0xFFC0C8C4), onNeutral = Color(0xFF2A322F),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/**
 * App theme: light + dark, brand colours by default. Material You dynamic colours are optional
 * ([dynamicColor]) and off by default so status colours keep a predictable contrast.
 */
@Composable
fun FamilyCircleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
