package io.github.frankgrobbelaar.tansu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    MaterialTheme(colorScheme = colors, typography = KiriTypography, shapes = KiriShapes) {
        // Text and icons with no colour of their own read on Kiri's background, light or dark:
        // Compose draws them black wherever no Surface or Scaffold says otherwise, as on the first
        // run and in Save to.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
