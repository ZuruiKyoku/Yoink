package com.zuruikyoku.yoink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Magpie Heist is always dark — the theme is the point, not a preference.
private val HeistColorScheme = darkColorScheme(
    primary = HeistYellow,
    onPrimary = HeistOnYellow,
    secondary = HeistYellow,
    onSecondary = HeistOnYellow,
    background = HeistBackground,
    onBackground = HeistOnBackground,
    surface = HeistSurface,
    onSurface = HeistOnBackground,
    surfaceVariant = HeistSurfaceVariant,
    onSurfaceVariant = HeistOnSurfaceMuted,
    outline = HeistOutline,
    error = HeistError,
    onError = HeistOnError
)

@Composable
fun YoinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HeistColorScheme,
        typography = YoinkTypography,
        content = content
    )
}
