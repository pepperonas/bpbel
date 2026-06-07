package io.celox.bpbel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Violet80,
    onPrimary = Surface0,
    primaryContainer = Violet40,
    onPrimaryContainer = Violet80,
    secondary = Magenta80,
    onSecondary = Surface0,
    secondaryContainer = Magenta60,
    tertiary = Cyan80,
    onTertiary = Surface0,
    tertiaryContainer = Cyan60,
    background = Surface0,
    onBackground = OnSurfaceHigh,
    surface = Surface1,
    onSurface = OnSurfaceHigh,
    surfaceVariant = Surface2,
    onSurfaceVariant = OnSurfaceDim,
    outline = OnSurfaceDim,
)

/**
 * Material 3 **Expressive** theme. We use [MaterialExpressiveTheme]
 * (not [androidx.compose.material3.MaterialTheme]) so every M3
 * component inherits the spring-based expressive [MotionScheme] by
 * default.
 *
 * bpbel is a dark, club-style audio visualizer: it **always** uses the
 * curated dark, high-saturation palette. Forcing dark (instead of
 * following the system / dynamic light color) guarantees the bright
 * readouts stay readable and preserves the brand identity — a light
 * system theme previously produced unreadable light-on-light text.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BpbelTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = DarkColors

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = BpbelTypography,
        // motionScheme / shapes intentionally omitted — defaults to the
        // expressive motion scheme + expressive shapes.
        content = content,
    )
}
