package io.github.frankgrobbelaar.tansu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Tansu's look: Kiri on Material 3. With [dynamicColor] the wallpaper's colours replace Kiri's;
 * type, shapes and motion stay.
 */
@Composable
fun TansuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors =
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            darkTheme -> KiriDark
            else -> KiriLight
        }
    MaterialTheme(
        colorScheme = colors,
        typography = KiriTypography,
        shapes = KiriShapes,
        content = content,
    )
}
