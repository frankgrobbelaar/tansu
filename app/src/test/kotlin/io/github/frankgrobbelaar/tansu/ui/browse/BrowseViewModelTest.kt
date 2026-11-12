package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.frankgrobbelaar.tansu.MainDispatcherRule
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem.Companion.file
import io.github.frankgrobbelaar.tansu.files.FakeFileSystem.Companion.folder
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val root = "/storage/emulated/0"
    private val download = "$root/Download"
    private val files =
        FakeFileSystem(
            mapOf(
                root to
                    listOf(
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
    private var granted = true

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        BrowseViewModel(
            Drawer("phone", "Phone", Drawer.Kind.Phone, root, files),
            { granted },
            saved,
        )

    @Test
    fun `opens on the drawer's top folder, folders first and hidden files left out`() = runTest {
        viewModel().state.test {
            val state = expectMostRecentItem()
            assertEquals("Phone", state.title)
            assertTrue(state.atRoot)
            assertEquals(listOf("Download", "Pictures"), state.entries.map { it.name })
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
        val browse = viewModel(SavedStateHandle(mapOf("path" to download)))
        browse.state.test {
            val crumbs = expectMostRecentItem().crumbs
            browse.jumpTo(crumbs.first())
            assertTrue(expectMostRecentItem().atRoot)
        }
    }

    @Test
    fun `without All files access the phone says so instead of listing`() = runTest {
        granted = false
        viewModel().state.test {
            val state = expectMostRecentItem()
            assertEquals(BrowseUiState.Problem.NoAccess, state.problem)
            assertTrue(state.entries.isEmpty())
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
        viewModel(SavedStateHandle(mapOf("path" to "/somewhere/else"))).state.test {
            assertTrue(expectMostRecentItem().atRoot)
        }
    }
}
