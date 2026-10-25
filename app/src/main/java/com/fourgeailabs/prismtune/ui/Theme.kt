package com.fourgeailabs.prismtune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** PrismTune dark theme. Amber-orange accent on deep charcoal — Plex-adjacent
 *  warmth, but its own palette and its own prism mark (see PrismLogo). */
val PrismAmber = Color(0xFFFFB020)
val PrismAmberDeep = Color(0xFFE8890C)
val PrismCharcoal = Color(0xFF14100C)
val PrismSurface = Color(0xFF1E1913)
val PrismOnSurface = Color(0xFFF5EFE6)

private val PrismColorScheme = darkColorScheme(
    primary = PrismAmber,
    onPrimary = Color.Black,
    secondary = PrismAmberDeep,
    background = PrismCharcoal,
    surface = PrismSurface,
    onBackground = PrismOnSurface,
    onSurface = PrismOnSurface,
)

@Composable
fun PrismTuneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PrismColorScheme, content = content)
}
