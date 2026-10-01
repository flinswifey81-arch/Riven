package com.shai.riven.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ArcadeColorScheme = darkColorScheme(
    primary = MutedGold,
    onPrimary = DeepInk,
    primaryContainer = TableNavyRaised,
    onPrimaryContainer = WarmIvory,
    secondary = MistBlue,
    onSecondary = DeepInk,
    tertiary = AquaHeart,
    background = PenthouseNavy,
    onBackground = WarmIvory,
    surface = TableNavy,
    onSurface = WarmIvory,
    surfaceVariant = TableNavyRaised,
    onSurfaceVariant = MistBlue,
    outline = MutedGold.copy(alpha = 0.62f),
    error = RubyHeart,
)

@Composable
fun RivenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArcadeColorScheme,
        typography = Typography,
        content = content,
    )
}
