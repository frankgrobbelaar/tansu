package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.frankgrobbelaar.tansu.StorageAccess
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.files.BrowseOrder
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.ui.kit.Crumb
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val crumbs: List<Crumb>,
    val entries: List<Entry> = emptyList(),
    val loading: Boolean = true,
    val problem: Problem? = null,
) {
    val title: String
        get() = crumbs.last().name

    val atRoot: Boolean
        get() = crumbs.size == 1

    enum class Problem {
        /** Android hasn't given tansu All files access. */
        NoAccess,
        /** This one folder is closed to tansu, such as Android/data. */
        Locked,
        /** The folder isn't there any more. */
        Missing,
    }
}

/** A drawer's folders: where Browse is, what's there, and moving around. */
class BrowseViewModel(
    private val drawer: Drawer,
    private val access: StorageAccess,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val path = savedState.getMutableStateFlow(PATH, drawer.root)
    private val listing = MutableStateFlow(Listing())
    private var loading: Job? = null

    val state: StateFlow<BrowseUiState> =
        combine(path, listing) { path, listing ->
                BrowseUiState(crumbsOf(path), listing.entries, listing.loading, listing.problem)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS),
                BrowseUiState(crumbsOf(path.value)),
            )

    init {
        if (!path.value.startsWith(drawer.root)) path.value = drawer.root
        viewModelScope.launch { path.collect { load(it, fresh = true) } }
    }

    fun open(entry: Entry) {
        if (entry.isFolder) path.value = entry.path
    }

    /** Goes up one folder, or says there's nowhere higher to go. */
    fun up(): Boolean {
        val crumbs = crumbsOf(path.value)
        if (crumbs.size < 2) return false
        path.value = crumbs[crumbs.lastIndex - 1].path
        return true
    }

    fun jumpTo(crumb: Crumb) {
        path.value = crumb.path
    }

    /** Lists the folder again, keeping what's shown until the new listing arrives. */
    fun refresh() {
        load(path.value, fresh = false)
    }

    private fun load(folder: String, fresh: Boolean) {
        loading?.cancel()
        loading = viewModelScope.launch {
            listing.update { if (fresh) Listing() else it.copy(loading = true) }
            listing.value = fetch(folder)
        }
    }

    private suspend fun fetch(folder: String): Listing =
        if (drawer.kind == Drawer.Kind.Phone && !access.granted()) {
            Listing(loading = false, problem = BrowseUiState.Problem.NoAccess)
        } else {
            try {
                val entries = drawer.fileSystem.list(folder).filterNot { it.isHidden }
                Listing(entries.sortedWith(BrowseOrder), loading = false)
            } catch (_: FileSystemException.AccessDenied) {
                Listing(loading = false, problem = BrowseUiState.Problem.Locked)
            } catch (_: FileSystemException.NotFound) {
                Listing(loading = false, problem = BrowseUiState.Problem.Missing)
            }
        }

    private fun crumbsOf(path: String): List<Crumb> {
        val names = path.removePrefix(drawer.root).split('/').filter { it.isNotEmpty() }
        val paths = names.runningFold(drawer.root) { parent, name -> join(parent, name) }.drop(1)
        return listOf(Crumb(drawer.name, drawer.root)) + names.zip(paths, ::Crumb)
    }

    private fun join(parent: String, name: String) =
        if (parent.endsWith('/')) parent + name else "$parent/$name"

    private data class Listing(
        val entries: List<Entry> = emptyList(),
        val loading: Boolean = true,
        val problem: BrowseUiState.Problem? = null,
    )

    companion object {
        private const val PATH = "path"
        private const val STOP_AFTER_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as TansuApplication).container
                BrowseViewModel(container.phone, container.storageAccess, createSavedStateHandle())
            }
        }
    }
}
