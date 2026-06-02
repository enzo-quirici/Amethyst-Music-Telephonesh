package com.qualcomm_toolbox.amethyst.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmethystColorScheme = darkColorScheme(
    primary = AmethystPrimary,
    onPrimary = Color.White,
    secondary = AmethystAccent,
    onSecondary = AmethystBackground,
    tertiary = AmethystAccent,
    background = AmethystBackground,
    onBackground = AmethystText,
    surface = AmethystPanel,
    onSurface = AmethystText,
    surfaceVariant = AmethystSearchBg,
    onSurfaceVariant = AmethystTextMuted,
    outline = AmethystBorder,
    error = AmethystDanger,
)

@Composable
fun AmethystMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmethystColorScheme,
        typography = Typography,
        content = content,
    )
}
