package io.github.frankgrobbelaar.tansu.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.frankgrobbelaar.tansu.TestTansuApplication
import io.github.frankgrobbelaar.tansu.ui.theme.TansuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestTansuApplication::class)
class TansuAppTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the navigation bar offers the three places`() {
        compose.setContent { TansuTheme { TansuApp() } }

        compose.onNodeWithText("Transfers").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `Transfers says nothing is moving when there are no jobs`() {
        compose.setContent { TansuTheme { TansuApp() } }

        compose.onNodeWithText("Transfers").performClick()

        compose.onNodeWithText("Nothing moving").assertIsDisplayed()
    }
}
