package io.github.frankgrobbelaar.tansu.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.frankgrobbelaar.tansu.TestTansuApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestTansuApplication::class)
class TansuThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `text with no colour of its own reads on Kiri's background, light or dark`() {
        // Compose would draw it black, as it did on the first run and in Save to in dark mode.
        var light = Color.Unspecified
        var dark = Color.Unspecified
        compose.setContent {
            TansuTheme(darkTheme = false) { light = LocalContentColor.current }
            TansuTheme(darkTheme = true) { dark = LocalContentColor.current }
        }
        compose.waitForIdle()

        assertEquals(KiriLight.onBackground, light)
        assertEquals(KiriDark.onBackground, dark)
    }
}
