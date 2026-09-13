package com.monopoly.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = MonopolyCream,
    primaryContainer = Color(0xFFCDE8D6),
    onPrimaryContainer = Color(0xFF07281A),
    secondary = RedAccent,
    onSecondary = MonopolyCream,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color(0xFFE6E3D8),
    onSurfaceVariant = Color(0xFF46483F),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = GreenPrimaryDark,
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF1B4D35),
    onPrimaryContainer = Color(0xFFB8EFD0),
    secondary = RedAccentDark,
    onSecondary = Color(0xFF5F1119),
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF42493F),
    onSurfaceVariant = Color(0xFFC2C9BD),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * Dynamic colour is deliberately not used.
 *
 * A Monopoly board is a strong, fixed piece of visual identity — eight printed
 * group colours that players recognise instantly. Re-tinting the interface from
 * the user's wallpaper would put arbitrary colours next to those, and the two
 * would fight. The app picks its own palette to sit alongside the board.
 */
@Composable
fun MonopolyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
