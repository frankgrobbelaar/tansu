package io.github.frankgrobbelaar.tansu.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Kiri in light: sand and paper surfaces, a wood-brown accent, sage beside it. See docs/design.md.
 */
internal val KiriLight =
    lightColorScheme(
        primary = Color(0xFF8A5A2B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF3DFC6),
        onPrimaryContainer = Color(0xFF4A2E12),
        inversePrimary = Color(0xFFE2B07A),
        secondary = Color(0xFF5E6B4E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE3E8D5),
        onSecondaryContainer = Color(0xFF2C3520),
        tertiary = Color(0xFF5E6B4E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3E8D5),
        onTertiaryContainer = Color(0xFF2C3520),
        background = Color(0xFFFBF7F1),
        onBackground = Color(0xFF2B2622),
        surface = Color(0xFFFBF7F1),
        onSurface = Color(0xFF2B2622),
        surfaceVariant = Color(0xFFEDE4D8),
        onSurfaceVariant = Color(0xFF6A5F55),
        surfaceTint = Color(0xFF8A5A2B),
        inverseSurface = Color(0xFF2B2622),
        inverseOnSurface = Color(0xFFF6EFE6),
        error = Color(0xFFB3412E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF6DCD4),
        onErrorContainer = Color(0xFF5A1A0E),
        outline = Color(0xFFA89A8A),
        outlineVariant = Color(0xFFDCCFBE),
        scrim = Color(0xFF2B2622),
        surfaceBright = Color(0xFFFBF7F1),
        surfaceDim = Color(0xFFE5D9C8),
        surfaceContainerLowest = Color(0xFFFFFDF9),
        surfaceContainerLow = Color(0xFFF6F0E7),
        surfaceContainer = Color(0xFFF1E9DE),
        surfaceContainerHigh = Color(0xFFEADFD0),
        surfaceContainerHighest = Color(0xFFE5D9C8),
    )

/** Kiri in dark: warm near-black, light wood for the accent. */
internal val KiriDark =
    darkColorScheme(
        primary = Color(0xFFE2B07A),
        onPrimary = Color(0xFF432A0E),
        primaryContainer = Color(0xFF5C3D1E),
        onPrimaryContainer = Color(0xFFFADDBA),
        inversePrimary = Color(0xFF8A5A2B),
        secondary = Color(0xFFB9C6A3),
        onSecondary = Color(0xFF253018),
        secondaryContainer = Color(0xFF3E4A31),
        onSecondaryContainer = Color(0xFFD9E4C6),
        tertiary = Color(0xFFB9C6A3),
        onTertiary = Color(0xFF253018),
        tertiaryContainer = Color(0xFF3E4A31),
        onTertiaryContainer = Color(0xFFD9E4C6),
        background = Color(0xFF1B1815),
        onBackground = Color(0xFFEDE4D9),
        surface = Color(0xFF1B1815),
        onSurface = Color(0xFFEDE4D9),
        surfaceVariant = Color(0xFF3A332C),
        onSurfaceVariant = Color(0xFFBFB2A3),
        surfaceTint = Color(0xFFE2B07A),
        inverseSurface = Color(0xFFEDE4D9),
        inverseOnSurface = Color(0xFF2B2622),
        error = Color(0xFFF0A08C),
        onError = Color(0xFF5A1A0E),
        errorContainer = Color(0xFF7A2A1A),
        onErrorContainer = Color(0xFFF6DCD4),
        outline = Color(0xFF8A7D70),
        outlineVariant = Color(0xFF4A4139),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF3A332C),
        surfaceDim = Color(0xFF1B1815),
        surfaceContainerLowest = Color(0xFF15120F),
        surfaceContainerLow = Color(0xFF221E1A),
        surfaceContainer = Color(0xFF26211C),
        surfaceContainerHigh = Color(0xFF312B25),
        surfaceContainerHighest = Color(0xFF3A332C),
    )
