package io.celox.bpbel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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

private val LightColors: ColorScheme = lightColorScheme(
    primary = Violet40,
    secondary = Magenta60,
    tertiary = Cyan60,
)

/**
 * Material 3 **Expressive** theme. We use [MaterialExpressiveTheme]
 * (not [androidx.compose.material3.MaterialTheme]) so every M3
 * component inherits the spring-based expressive [MotionScheme] by
 * default. Dynamic color is used on Android 12+ when available.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BpbelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = BpbelTypography,
        // motionScheme / shapes intentionally omitted — defaults to the
        // expressive motion scheme + expressive shapes.
        content = content,
    )
}
