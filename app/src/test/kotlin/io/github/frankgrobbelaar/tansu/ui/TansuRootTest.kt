package io.github.frankgrobbelaar.tansu.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.frankgrobbelaar.tansu.TestTansuApplication
import io.github.frankgrobbelaar.tansu.ui.theme.TansuTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestTansuApplication::class)
class TansuRootTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun grantAgain() {
        TestTansuApplication.granted = true
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun `without All files access, the first run says what tansu is`() {
        TestTansuApplication.granted = false
        compose.setContent { TansuTheme { TansuRoot() } }

        waitFor("Get started")
    }

    @Test
    fun `with access, tansu opens on Browse and the first run is over`() {
        compose.setContent { TansuTheme { TansuRoot() } }

        waitFor("Browse")
        val settings =
            ApplicationProvider.getApplicationContext<TestTansuApplication>().container.settings
        runBlocking { withTimeout(5_000) { settings.welcomeDone.first { it } } }
    }
}
