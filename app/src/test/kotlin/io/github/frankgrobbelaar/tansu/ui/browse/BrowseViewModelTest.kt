package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.frankgrobbelaar.tansu.MainDispatcherRule
import io.github.frankgrobbelaar.tansu.MemoryStore
import io.github.frankgrobbelaar.tansu.TansuSettings
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem.Companion.file
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem.Companion.folder
import io.github.frankgrobbelaar.tansu.files.Fetcher
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.HostKey
import io.github.frankgrobbelaar.tansu.files.ListsShares
import io.github.frankgrobbelaar.tansu.files.MemoryFileSystem
import io.github.frankgrobbelaar.tansu.files.Sort
import io.github.frankgrobbelaar.tansu.files.SortBy
import io.github.frankgrobbelaar.tansu.jobs.Clipboard
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.jobs.JobRun
import io.github.frankgrobbelaar.tansu.jobs.Jobs
import io.github.frankgrobbelaar.tansu.jobs.Where
import io.github.frankgrobbelaar.tansu.servers.SavedServers
import io.github.frankgrobbelaar.tansu.servers.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()

    private class FakeServers : SavedServers {
        override val all = MutableStateFlow(emptyList<Server>())
        override val added = MutableSharedFlow<Server>(extraBufferCapacity = 1)
        val trusted = mutableMapOf<String, String>()
        val shares = mutableMapOf<String, String>()
        val removed = mutableListOf<String>()

        override suspend fun trustKey(serverId: String, fingerprint: String) {
            trusted[serverId] = fingerprint
        }

        override suspend fun useShare(serverId: String, share: String) {
            shares[serverId] = share
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
    private val jobs =
        Jobs(drawers, CoroutineScope(SupervisorJob() + main.dispatcher), io = main.dispatcher)
    private val clipboard = Clipboard()
    private var granted = true

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle(), jobs: Jobs = this.jobs) =
        BrowseViewModel(
            drawers = drawers,
            access = { granted },
            servers = servers,
            settings = settings,
            jobs = jobs,
            clipboard = clipboard,
            fetcher = Fetcher(folder.root, io = main.dispatcher),
            savedState = saved,
            compute = main.dispatcher,
        )

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
    fun `hidden files show when Settings says so, and select all picks only what shows`() =
        runTest {
            val browse = viewModel()
            browse.state.test {
                settings.setShowHidden(true)
                assertEquals(
                    listOf("Download", "Pictures", ".hidden", "notes.txt"),
                    expectMostRecentItem().entries.map { it.name },
                )
                browse.selectAll()
                assertEquals(4, expectMostRecentItem().selected.size)

                // Turned off: nothing stays picked that can't be seen.
                settings.setShowHidden(false)
                assertTrue(expectMostRecentItem().selected.isEmpty())
                browse.selectAll()
                assertEquals(
                    setOf("$root/Download", "$root/Pictures", "$root/notes.txt"),
                    expectMostRecentItem().selected,
                )
            }
        }

    @Test
    fun `searching shows what matches, in any case, and ends with the folder`() = runTest {
        val browse = viewModel()
        browse.state.test {
            browse.search("")
            assertEquals(3, expectMostRecentItem().entries.size)
            browse.search("NOTES")
            val found = expectMostRecentItem()
            assertEquals("NOTES", found.search)
            assertEquals(listOf("notes.txt"), found.entries.map { it.name })

            browse.open(folder(root, "Download"))
            val moved = expectMostRecentItem()
            assertFalse(moved.searching)
            assertEquals(listOf("Receipts", "a.pdf", "b.pdf"), moved.entries.map { it.name })
        }
    }

    @Test
    fun `the order applies to every folder, and each folder keeps its own view`() = runTest {
        val browse = viewModel()
        browse.state.test {
            browse.setSort(Sort(SortBy.Name, descending = true))
            browse.setGrid(true)
            val top = expectMostRecentItem()
            assertEquals(listOf("Pictures", "Download", "notes.txt"), top.entries.map { it.name })
            assertTrue(top.grid)

            browse.open(folder(root, "Download"))
            val inside = expectMostRecentItem()
            assertEquals(listOf("Receipts", "b.pdf", "a.pdf"), inside.entries.map { it.name })
            assertFalse(inside.grid)

            browse.up()
            assertTrue(expectMostRecentItem().grid)
        }
    }

    @Test
    fun `a folder asked for from outside opens, in a drawer that's there`() = runTest {
        val browse = viewModel()
        browse.state.test {
            browse.showFolder("gone", "/somewhere")
            assertTrue(expectMostRecentItem().atRoot)
            browse.showFolder("phone", download)
            assertEquals(listOf("Phone", "Download"), expectMostRecentItem().crumbs.map { it.name })
            browse.up()
            assertTrue(expectMostRecentItem().atRoot)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a server slow to answer is said to be waited for`() = runTest {
        // As a NAS whose disks were asleep, or one reached through Tailscale as it wakes up.
        val sleepy =
            object : FileSystem by nasFiles {
                override suspend fun list(path: String): List<Entry> {
                    delay(SLEEPY_MILLIS)
                    return nasFiles.list(path)
                }
            }
        drawers.value = listOf(phone, nas.copy(fileSystem = sleepy))
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            advanceTimeBy(1_000)
            assertFalse(browse.state.value.slow)

            advanceTimeBy(3_000)
            assertTrue(browse.state.value.loading && browse.state.value.slow)

            advanceTimeBy(SLEEPY_MILLIS)
            val shown = browse.state.value
            assertFalse(shown.loading || shown.slow)
            assertEquals(listOf("Films"), shown.entries.map { it.name })
            cancelAndIgnoreRemainingEvents()
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
    fun `a missing share lists the shares the server has, and the one picked is listed`() =
        runTest {
            val gone =
                object : FileSystem by nasFiles, ListsShares {
                    override suspend fun list(path: String): List<Entry> =
                        throw FileSystemException.ShareMissing("Media")

                    override suspend fun shares() = listOf("Backups", "Media 2026")
                }
            drawers.value = listOf(phone, nas.copy(fileSystem = gone))
            val browse = viewModel()
            browse.state.test {
                browse.select("nas")
                val missing = expectMostRecentItem()
                assertEquals(
                    BrowseUiState.Problem.ShareMissing("Media", listOf("Backups", "Media 2026")),
                    missing.problem,
                )
                assertTrue(missing.drawers.single { it.id == "nas" }.troubled)

                browse.useShare("Media 2026")
                assertEquals(mapOf("nas" to "Media 2026"), servers.shares)

                // The server, saved with the share picked, comes back as the drawer.
                val moved = nasServer.copy(share = "Media 2026")
                drawers.value = listOf(phone, nas.copy(fileSystem = nasFiles, server = moved))
                val listed = expectMostRecentItem()
                assertNull(listed.problem)
                assertEquals(listOf("Films"), listed.entries.map { it.name })
                assertFalse(listed.drawers.single { it.id == "nas" }.troubled)
            }
        }

    @Test
    fun `a share the server has but won't open says so, rather than offering it`() = runTest {
        val unopened =
            object : FileSystem by nasFiles, ListsShares {
                override suspend fun list(path: String): List<Entry> =
                    throw FileSystemException.ShareMissing("Media")

                // Samba says a share isn't there when it can't open its folder.
                override suspend fun shares() = listOf("Backups", "media")
            }
        drawers.value = listOf(phone, nas.copy(fileSystem = unopened))
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            val state = expectMostRecentItem()
            assertEquals(BrowseUiState.Problem.ShareUnavailable("Media"), state.problem)
            assertTrue(state.drawers.single { it.id == "nas" }.troubled)
        }
    }

    @Test
    fun `a share picked is listed from its top`() = runTest {
        val media =
            FakeFileSystem(
                mapOf(
                    "/" to listOf(folder("", "Films")),
                    "/Films" to listOf(file("/Films", "a.mkv")),
                )
            )
        val going =
            object : FileSystem by media, ListsShares {
                override suspend fun shares() = listOf("Music")
            }
        drawers.value = listOf(phone, nas.copy(fileSystem = going))
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            browse.open(folder("", "Films"))
            assertEquals(listOf("a.mkv"), expectMostRecentItem().entries.map { it.name })

            // The share goes, and another is picked, without a Films folder.
            media.failures["/Films"] = FileSystemException.ShareMissing("Media")
            browse.refresh()
            assertEquals(
                BrowseUiState.Problem.ShareMissing("Media", listOf("Music")),
                expectMostRecentItem().problem,
            )
            browse.useShare("Music")
            val music = FakeFileSystem(mapOf("/" to listOf(folder("", "Albums"))))
            val moved = nasServer.copy(share = "Music")
            drawers.value = listOf(phone, nas.copy(fileSystem = music, server = moved))

            val listed = expectMostRecentItem()
            assertNull(listed.problem)
            assertTrue(listed.atRoot)
            assertEquals(listOf("Albums"), listed.entries.map { it.name })
        }
    }

    @Test
    fun `a missing share on a server that can't list its shares is just missing`() = runTest {
        val refusing =
            object : FileSystem by nasFiles, ListsShares {
                override suspend fun list(path: String): List<Entry> =
                    throw FileSystemException.ShareMissing("Media")

                override suspend fun shares(): List<String> =
                    throw FileSystemException.AccessDenied("IPC$")
            }
        drawers.value = listOf(phone, nas.copy(fileSystem = refusing))
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            assertEquals(
                BrowseUiState.Problem.ShareMissing("Media"),
                expectMostRecentItem().problem,
            )
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

    @Test
    fun `picking files selects them, and moving to another folder lets go`() = runTest {
        val browse = viewModel()
        browse.state.test {
            browse.toggle(folder(root, "Download"))
            browse.toggle(file(root, "notes.txt"))
            val picking = expectMostRecentItem()
            assertTrue(picking.selecting)
            assertEquals(listOf("Download", "notes.txt"), picking.picked.map { it.name })

            browse.open(folder(root, "Download"))
            assertFalse(expectMostRecentItem().selecting)
        }
    }

    @Test
    fun `copy holds the picks, and a clash in the new folder is found before pasting`() = runTest {
        val browse = viewModel()
        browse.state.test {
            browse.toggle(file(root, "notes.txt"))
            browse.copy()
            val holding = expectMostRecentItem()
            assertFalse(holding.selecting)
            assertEquals(listOf("notes.txt"), holding.held?.picks?.map { it.name })

            browse.open(folder(root, "Download"))
            val inDownload = expectMostRecentItem()
            assertTrue(browse.clashes().isEmpty())
            browse.jumpTo(inDownload.crumbs.first())
            expectMostRecentItem()
            assertEquals(listOf("notes.txt"), browse.clashes().map { it.here.name })
        }
    }

    @Test
    fun `pasting starts a job into this folder and lets go of what was held`() = runTest {
        val browse = viewModel()
        browse.state.test {
            browse.toggle(file(root, "notes.txt"))
            browse.cut()
            browse.open(folder(root, "Download"))
            browse.paste(emptyMap())

            val job = jobs.all.value.single()
            assertEquals(Job.Kind.Move, job.kind)
            assertEquals(download, job.to?.folder)
            assertNull(expectMostRecentItem().held)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `pasted files show up as they land, not only when the job ends`() = runTest {
        val shelf = MemoryFileSystem().file("/a.mkv", "a").file("/b.mkv", "b").folderAt("/Films")
        drawers.value = listOf(phone, nas.copy(fileSystem = shelf))
        val held = CompletableDeferred<Unit>()
        shelf.gates["/b.mkv"] = held
        // A job says how far it got every so often: here, every time.
        var now = 0L
        val jobs =
            Jobs(
                drawers,
                CoroutineScope(SupervisorJob() + main.dispatcher),
                io = main.dispatcher,
                clock = { now.also { now += JobRun.REPORT_EVERY_MILLIS } },
            )
        val browse = viewModel(jobs = jobs)
        browse.state.test {
            browse.select("nas")
            browse.open(folder("", "Films"))
            val nasHere = Where("nas", "/", "NAS", "NAS")
            val films = Where("nas", "/Films", "NAS", "NAS › Films")
            val picks = listOf("/a.mkv", "/b.mkv").map { checkNotNull(shelf.stat(it)) }

            jobs.copy(picks, nasHere, films, emptyMap())
            advanceTimeBy(1_000)

            // The first is there while the second is still on its way.
            assertTrue(jobs.all.value.single().active)
            assertEquals(listOf("a.mkv"), browse.state.value.entries.map { it.name })

            held.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("a.mkv", "b.mkv"), browse.state.value.entries.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `files landing while Browse isn't on screen aren't listed for`() = runTest {
        // In the background, a long job would have it list a server every couple of seconds.
        val shelf = MemoryFileSystem().file("/a.mkv", "a").file("/b.mkv", "b").folderAt("/Films")
        var listings = 0
        val counted =
            object : FileSystem by shelf {
                override suspend fun list(path: String): List<Entry> {
                    if (path == "/Films") listings++
                    return shelf.list(path)
                }
            }
        drawers.value = listOf(phone, nas.copy(fileSystem = counted))
        val held = CompletableDeferred<Unit>()
        shelf.gates["/b.mkv"] = held
        var now = 0L
        val jobs =
            Jobs(
                drawers,
                CoroutineScope(SupervisorJob() + main.dispatcher),
                io = main.dispatcher,
                clock = { now.also { now += JobRun.REPORT_EVERY_MILLIS } },
            )
        val browse = viewModel(jobs = jobs)
        browse.select("nas")
        browse.open(folder("", "Films"))
        val before = listings
        val picks = listOf("/a.mkv", "/b.mkv").map { checkNotNull(shelf.stat(it)) }

        jobs.copy(
            picks,
            Where("nas", "/", "NAS", "NAS"),
            Where("nas", "/Films", "NAS", "NAS › Films"),
            emptyMap(),
        )
        advanceTimeBy(LANDING_WHILE_MILLIS)

        // Only the job's own look at where it's copying to.
        assertEquals(before + 1, listings)
        held.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a search is kept when Android closes tansu and brings it back`() = runTest {
        val saved = SavedStateHandle()
        viewModel(saved).search("pdf")

        viewModel(saved).state.test {
            var back = awaitItem()
            while (!back.searching) back = awaitItem()
            assertEquals("pdf", back.search)
        }
    }

    @Test
    fun `a listing given up on can't land on the drawer you moved to`() = runTest {
        // smbj and sshj can't be stopped: the old listing still ends, here with a failure.
        val gate = CompletableDeferred<Unit>()
        val stubborn =
            object : FileSystem by MemoryFileSystem() {
                override suspend fun list(path: String): List<Entry> =
                    withContext(NonCancellable) {
                        gate.await()
                        throw FileSystemException.Unreachable("nas")
                    }
            }
        drawers.value = listOf(phone, nas.copy(fileSystem = stubborn))
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            browse.select("phone")
            gate.complete(Unit)

            val state = expectMostRecentItem()
            assertEquals("phone", state.drawer.id)
            assertNull(state.problem)
            assertFalse(state.drawers.single { it.id == "nas" }.troubled)
        }
    }

    @Test
    fun `a server file comes to the phone before another app can open it`() = runTest {
        val serverFiles = MemoryFileSystem().file("/notes.md", "hello")
        drawers.value = listOf(phone, nas.copy(fileSystem = serverFiles))
        val browse = viewModel()
        browse.state.test {
            browse.select("nas")
            browse.fetch(listOf(serverFiles.stat("/notes.md")!!), BrowseUiState.Purpose.Open)

            val fetched = expectMostRecentItem().fetched
            assertEquals(BrowseUiState.Purpose.Open, fetched?.purpose)
            assertEquals("hello", fetched?.files?.single()?.readText())

            browse.fetchedHandled()
            assertNull(expectMostRecentItem().fetched)
        }
    }

    @Test
    fun `a rename whose answer was lost renames once and says nothing`() = runTest {
        val films = MemoryFileSystem().file("/a.mkv", "a")
        films.lostAnswers += "/b.mkv"
        drawers.value = listOf(phone, Drawer("films", "Films", Drawer.Kind.Sftp, "/", films))
        val browse = viewModel()
        browse.state.test {
            browse.select("films")
            browse.rename(expectMostRecentItem().entries.single(), "b.mkv")
            val renamed = expectMostRecentItem()
            assertNull(renamed.notice)
            assertEquals(listOf("b.mkv"), renamed.entries.map { it.name })
        }
    }

    @Test
    fun `a rename on a connection that had died is tried again`() = runTest {
        val films = MemoryFileSystem().file("/a.mkv", "a")
        val dropsOnce =
            object : FileSystem by films {
                var dead = true

                override suspend fun rename(from: String, to: String, replace: Boolean) {
                    if (dead) {
                        dead = false
                        throw FileSystemException.Unreachable("films")
                    }
                    films.rename(from, to, replace)
                }
            }
        drawers.value = listOf(phone, Drawer("films", "Films", Drawer.Kind.Sftp, "/", dropsOnce))
        val browse = viewModel()
        browse.state.test {
            browse.select("films")
            browse.rename(expectMostRecentItem().entries.single(), "b.mkv")
            val renamed = expectMostRecentItem()
            assertNull(renamed.notice)
            assertEquals(listOf("b.mkv"), renamed.entries.map { it.name })
        }
    }

    private companion object {
        const val SLEEPY_MILLIS = 5_000L
        const val LANDING_WHILE_MILLIS = 10_000L
    }
}
