package com.iptv.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

// `primary` is the brand fill — buttons, selected nav/chips, the progress
// bar. `secondary` is reserved exclusively for focus/attention (see the
// AccentOrange doc in Color.kt) and nothing else should read from it, or
// "orange" stops reliably meaning "the remote will act here".
private val DarkColors = darkColorScheme(
    primary = PrimaryGreen,
    onPrimary = OnAccent,
    secondary = AccentOrange,
    onSecondary = OnAccent,
    background = NightBackground,
    onBackground = TextOnDark,
    surface = NightSurface,
    onSurface = TextOnDark,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = TextOnDarkMuted,
    border = NightBorder,
    error = ErrorRed,
    onError = OnAccent,
)

private val LightColors = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = OnAccent,
    secondary = AccentOrange,
    onSecondary = OnAccent,
    background = DayBackground,
    onBackground = TextOnLight,
    surface = DaySurface,
    onSurface = TextOnLight,
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = TextOnLightMuted,
    border = DayBorder,
    error = ErrorRed,
    onError = OnAccent,
)

/**
 * True when the app is drawing in its light theme. A few components (scrims
 * over artwork, the guide's now-line) need to know this beyond what the colour
 * scheme alone expresses.
 */
val LocalIsLightTheme = staticCompositionLocalOf { false }

@Composable
fun IptvTheme(
    light: Boolean = false,
    /**
     * User-facing UI scale from Settings. Applied by multiplying the density
     * rather than by scaling every dimension: it moves text, tiles, paddings
     * and focus rings together, and it is the only approach that stays correct
     * when the same APK runs on a 1080p 32" panel and a 4K 75" one.
     */
    uiScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    val scaled = Density(density = base.density * uiScale, fontScale = base.fontScale)
    val colors = if (light) LightColors else DarkColors

    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalIsLightTheme provides light,
        // tv-material only derives a content colour inside its own Surface, so
        // any Text drawn straight onto the background inherits the default —
        // black — and vanishes against a dark theme. Providing it at the root
        // means a Text with no explicit colour is always readable.
        LocalContentColor provides colors.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = TvTypography,
            content = content,
        )
    }
}
