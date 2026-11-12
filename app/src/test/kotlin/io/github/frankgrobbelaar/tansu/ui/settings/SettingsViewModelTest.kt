package io.github.frankgrobbelaar.tansu.ui.settings

import app.cash.turbine.test
import io.github.frankgrobbelaar.tansu.MainDispatcherRule
import io.github.frankgrobbelaar.tansu.MemoryStore
import io.github.frankgrobbelaar.tansu.TansuSettings
import io.github.frankgrobbelaar.tansu.Theme
import io.github.frankgrobbelaar.tansu.servers.SavedServers
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.updates.Update
import io.github.frankgrobbelaar.tansu.updates.Updates
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()

    private class FakeServers : SavedServers {
        override val all = MutableStateFlow(emptyList<Server>())
        override val added = MutableSharedFlow<Server>()

        override suspend fun trustKey(serverId: String, fingerprint: String) = Unit

        override suspend fun remove(serverId: String) = Unit
    }

    private val apk = "the next tansu".toByteArray()
    private val settings = TansuSettings(MemoryStore())
    private val servers = FakeServers()

    private fun get(url: String): InputStream =
        when {
            url == Updates.LATEST ->
                """
                {"tag_name": "v0.2.0", "body": "- Settings.", "assets": [
                  {"name": "tansu.apk", "size": ${apk.size}, "browser_download_url": "${Updates.DOWNLOADS}apk"},
                  {"name": "tansu.apk.sha256", "size": 65, "browser_download_url": "${Updates.DOWNLOADS}sum"}
                ]}
                """
                    .byteInputStream()
            url == "${Updates.DOWNLOADS}apk" -> apk.inputStream()
            url == "${Updates.DOWNLOADS}sum" -> sha256(apk).byteInputStream()
            else -> throw IOException("HTTP 404 from $url")
        }

    private val updates by lazy {
        Updates(
            installed = "0.1.0",
            cache = folder.root,
            settings = settings,
            scope = CoroutineScope(SupervisorJob() + main.dispatcher),
            get = ::get,
            io = main.dispatcher,
        )
    }

    private fun viewModel() = SettingsViewModel(settings, servers, updates, version = "0.1.0")

    @Test
    fun `shows the choices, the servers and the version, and changes the choices`() = runTest {
        val nas = Server("nas", "NAS", Server.Kind.Smb, "nas", 445, share = "Media")
        servers.all.value = listOf(nas)
        val settingsScreen = viewModel()
        settingsScreen.state.test {
            settingsScreen.setTheme(Theme.Dark)
            settingsScreen.setShowHidden(true)
            settingsScreen.setCheckUpdates(false)
            val state = expectMostRecentItem()
            assertEquals(Theme.Dark, state.choices.theme)
            assertTrue(state.choices.showHidden)
            assertEquals(false, state.choices.checkUpdates)
            assertEquals(listOf(nas), state.servers)
            assertEquals("0.1.0", state.version)
        }
    }

    @Test
    fun `download and install hands the checked APK to the installer once`() = runTest {
        val settingsScreen = viewModel()
        settingsScreen.state.test {
            settingsScreen.checkNow()
            assertTrue(expectMostRecentItem().update is Update.Available)

            settingsScreen.downloadAndInstall()
            val ready = expectMostRecentItem()
            assertTrue(ready.update is Update.Ready)
            assertTrue(ready.install!!.readBytes().contentEquals(apk))

            settingsScreen.installHandled()
            assertNull(expectMostRecentItem().install)

            // Turned down at Android's prompt: Install offers it again.
            settingsScreen.install()
            assertEquals((ready.update as Update.Ready).apk, expectMostRecentItem().install)
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
