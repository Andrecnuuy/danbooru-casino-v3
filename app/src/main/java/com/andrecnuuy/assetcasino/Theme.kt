package com.andrecnuuy.assetcasino

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFF5C451)
val CasinoRed = Color(0xFFE0475B)
val Felt = Color(0xFF4CC38A)

// Danbooru's tag colours (dark theme variants)
val TagArtist = Color(0xFFFF8A8B)
val TagCopyright = Color(0xFFC797FF)
val TagCharacter = Color(0xFF35C64A)

private val CasinoColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF1B1426),
    secondary = CasinoRed,
    onSecondary = Color.White,
    tertiary = Felt,
    background = Color(0xFF14101C),
    onBackground = Color(0xFFEDE7F6),
    surface = Color(0xFF1E1830),
    onSurface = Color(0xFFEDE7F6),
    surfaceVariant = Color(0xFF2A2340),
    onSurfaceVariant = Color(0xFFB8AFCB),
    secondaryContainer = Color(0xFF4B3B14),
    onSecondaryContainer = Color(0xFFFFE9A8),
    outline = Color(0xFF6E6490),
    error = Color(0xFFFF6B6B)
)

@Composable
fun CasinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CasinoColors, content = content)
}
