package io.github.frankgrobbelaar.tansu.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.frankgrobbelaar.tansu.Theme
import io.github.frankgrobbelaar.tansu.appContainer
import io.github.frankgrobbelaar.tansu.ui.theme.TansuTheme

/**
 * Tansu's look as chosen in Settings: light, dark or as the phone is, in Kiri's colours or the
 * wallpaper's. Nothing is drawn until the choice is read, so the wrong theme never flashes.
 */
@Composable
fun ChosenTheme(content: @Composable () -> Unit) {
    val choices by appContainer().settings.choices.collectAsStateWithLifecycle(initialValue = null)
    val chosen = choices ?: return
    val dark =
        when (chosen.theme) {
            Theme.System -> isSystemInDarkTheme()
            Theme.Light -> false
            Theme.Dark -> true
        }
    // The status and navigation bars' icons follow tansu's theme, not the phone's.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        onDispose {}
    }
    TansuTheme(darkTheme = dark, dynamicColor = chosen.dynamicColor, content = content)
}

// The scrims Android's own edge-to-edge puts behind three-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
