package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.frankgrobbelaar.tansu.MainDispatcherRule
import io.github.frankgrobbelaar.tansu.MemoryStore
import io.github.frankgrobbelaar.tansu.TansuSettings
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem.Companion.file
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem.Companion.folder
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.HostKey
import io.github.frankgrobbelaar.tansu.servers.SavedServers
import io.github.frankgrobbelaar.tansu.servers.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeServers : SavedServers {
        override val all = MutableStateFlow(emptyList<Server>())
        override val added = MutableSharedFlow<Server>(extraBufferCapacity = 1)
        val trusted = mutableMapOf<String, String>()
        val removed = mutableListOf<String>()

        override suspend fun trustKey(serverId: String, fingerprint: String) {
            trusted[serverId] = fingerprint
        }

        override suspend fun remove(serverId: String) {
            removed += serverId
        }
    }

    private val root = "/storage/emulated/0"
    private val download = "$root/Download"
    private val files =
        FakeFileSystem(
            mapOf(
                root to
                    listOf(
                        file(root, "notes.txt"),
                        folder(root, "Pictures"),
                        folder(root, "Download"),
                        file(root, ".hidden"),
                    ),
                download to
                    listOf(
                        file(download, "b.pdf"),
                        folder(download, "Receipts"),
                        file(download, "a.pdf"),
                    ),
            )
        )
    private val nasFiles = FakeFileSystem(mapOf("/" to listOf(folder("", "Films"))))
    private val phone = Drawer("phone", "Phone", Drawer.Kind.Phone, root, files)
    private val nasServer = Server("nas", "NAS", Server.Kind.Smb, "nas", 445, share = "Media")
    private val nas = Drawer("nas", "NAS", Drawer.Kind.Smb, "/", nasFiles, nasServer)
    private val drawers = MutableStateFlow(listOf(phone))
    private val servers = FakeServers()
    private val settings = TansuSettings(MemoryStore())
    private var granted = true

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        BrowseViewModel(drawers, { granted }, servers, settings, saved, main.dispatcher)

    @Test
    fun `opens on the phone's top folder, folders first and hidden files left out`() = runTest {
        viewModel().state.test {
            val state = expectMostRecentItem()
            assertEquals("Phone", state.title)
            assertTrue(state.atRoot)
            assertEquals(listOf("Download", "Pictures", "notes.txt"), state.entries.map { it.name })
        }
    }

    @Test
    fun `opening a folder lists it and puts it on the path, and up comes back`() = runTest {
        val browse = viewModel()
        browse.state.test {
            browse.open(folder(root, "Download"))
            val inside = expectMostRecentItem()
            assertEquals(listOf("Phone", "Download"), inside.crumbs.map { it.name })
            assertEquals(listOf("Receipts", "a.pdf", "b.pdf"), inside.entries.map { it.name })

            assertTrue(browse.up())
            assertTrue(expectMostRecentItem().atRoot)
            assertFalse(browse.up())
        }
    }

    @Test
    fun `a crumb jumps straight to its folder`() = runTest {
        val browse = viewModel(SavedStateHandle(mapOf("paths" to mapOf("phone" to download))))
        browse.state.test {
            val crumbs = expectMostRecentItem().crumbs
            assertEquals(2, crumbs.size)
            browse.jumpTo(crumbs.first())
            assertTrue(expectMostRecentItem().atRoot)
        }
    }

    @Test
    fun `without All files access the phone says so, and lists once access is given`() = runTest {
        granted = false
        val browse = viewModel()
        browse.state.test {
            val state = expectMostRecentItem()
            assertEquals(BrowseUiState.Problem.NoAccess, state.problem)
            assertTrue(state.entries.isEmpty())

            granted = true
            browse.refresh()
            assertEquals(3, expectMostRecentItem().entries.size)
        }
    }

    @Test
    fun `a closed folder and a missing folder each say what happened`() = runTest {
        files.failures[download] = FileSystemException.AccessDenied(download)
        val browse = viewModel()
        browse.state.test {
            browse.open(folder(root, "Download"))
            assertEquals(BrowseUiState.Problem.Locked, expectMostRecentItem().problem)

            browse.open(folder(root, "Gone"))
            assertEquals(BrowseUiState.Problem.Missing, expectMostRecentItem().problem)
        }
    }

    @Test
    fun `a saved path outside the drawer starts at the top instead`() = runTest {
        val saved = SavedStateHandle(mapOf("paths" to mapOf("phone" to "/somewhere/else")))
        viewModel(saved).state.test { assertTrue(expectMostRecentItem().atRoot) }
    }

    @Test
    fun `a slow listing of the folder you left can't replace the one you opened`() = runTest {
        val slow = CompletableDeferred<Unit>()
        files.gates[download] = slow
        val browse = viewModel()
        browse.state.test {
            browse.open(folder(root, "Download"))
            browse.up()
            slow.complete(Unit)

            val state = expectMostRecentItem()
            assertTrue(state.atRoot)
            assertEquals(3, state.entries.size)
        }
    }

    @Test
    fun `refreshing keeps the files on show until the new listing arrives`() = runTest {
        val browse = viewModel()
        browse.state.test {
            assertEquals(3, expectMostRecentItem().entries.size)
            val slow = CompletableDeferred<Unit>()
            files.gates[root] = slow

            browse.refresh()
            val refreshing = expectMostRecentItem()
            assertTrue(refreshing.loading)
            assertEquals(3, refreshing.entries.size)

            slow.complete(Unit)
            assertFalse(expectMostRecentItem().loading)
        }
    }

    @Test
    fun `refreshing while a listing is on its way doesn't start another`() = runTest {
        val slow = CompletableDeferred<Unit>()
        files.gates[root] = slow
        val browse = viewModel()

        browse.refresh()
        slow.complete(Unit)

        assertEquals(1, files.listings)
    }

    @Test
    fun `each drawer remembers its own folder`() = runTest {
        drawers.value = listOf(phone, nas)
        val browse = viewModel()
        browse.state.test {
            browse.open(folder(root, "Download"))
            browse.select("nas")
            val onNas = expectMostRecentItem()
            assertEquals("NAS", onNas.title)
            assertEquals(listOf("Films"), onNas.entries.map { it.name })

            browse.select("phone")
            assertEquals("Download", expectMostRecentItem().title)
        }
    }

    @Test
    fun `a server that doesn't answer says so and gets a dot until it does`() = runTest {
        drawers.value = listOf(phone, nas)
        nasFiles.failures["/"] = FileSystemException.Unreachable("nas")
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            val down = expectMostRecentItem()
            assertEquals(BrowseUiState.Problem.Unreachable("nas"), down.problem)
            assertTrue(down.drawers.single { it.id == "nas" }.troubled)

            nasFiles.failures.clear()
            browse.refresh()
            val back = expectMostRecentItem()
            assertNull(back.problem)
            assertFalse(back.drawers.single { it.id == "nas" }.troubled)
        }
    }

    @Test
    fun `a changed key blocks the server until its new key is trusted`() = runTest {
        drawers.value = listOf(phone, nas)
        val sent = HostKey("ED25519", "SHA256:new")
        nasFiles.failures["/"] = FileSystemException.HostKeyChanged("SHA256:old", sent)
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            assertEquals(
                BrowseUiState.Problem.KeyChanged("SHA256:old", sent),
                expectMostRecentItem().problem,
            )

            browse.trustNewKey()
            assertEquals("SHA256:new", servers.trusted["nas"])
        }
    }

    @Test
    fun `a new server's drawer opens as soon as it's added`() = runTest {
        val browse = viewModel()
        browse.state.test {
            servers.added.emit(nasServer)
            drawers.value = listOf(phone, nas)

            val state = expectMostRecentItem()
            assertEquals("nas", state.drawer.id)
            assertEquals("NAS", state.added)

            browse.addedShown()
            assertNull(expectMostRecentItem().added)
        }
    }

    @Test
    fun `removing a server goes back to the phone`() = runTest {
        drawers.value = listOf(phone, nas)
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            browse.removeServer()
            drawers.value = listOf(phone)

            assertEquals("phone", expectMostRecentItem().drawer.id)
            assertEquals(listOf("nas"), servers.removed)
        }
    }

    @Test
    fun `the offer to add a server shows until one is added or it's turned down`() = runTest {
        val browse = viewModel()
        browse.state.test {
            assertTrue(expectMostRecentItem().offerServers)

            browse.dismissServerOffer()
            assertFalse(expectMostRecentItem().offerServers)
        }
    }
}
