package io.github.frankgrobbelaar.tansu.ui

import androidx.annotation.StringRes
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.appContainer
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.ui.browse.BrowseRoute
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.servers.AddServerScreen
import io.github.frankgrobbelaar.tansu.ui.servers.ServerFormRoute
import io.github.frankgrobbelaar.tansu.ui.settings.SettingsScreen
import io.github.frankgrobbelaar.tansu.ui.transfers.JobRoute
import io.github.frankgrobbelaar.tansu.ui.transfers.TransfersRoute
import kotlinx.serialization.Serializable

/** The three places in the navigation bar. Each keeps its own history. */
enum class Place(@param:StringRes val label: Int, val icon: ImageVector) {
    Browse(R.string.nav_browse, TansuIcons.Folder),
    Transfers(R.string.nav_transfers, TansuIcons.Transfers),
    Settings(R.string.nav_settings, TansuIcons.Settings),
}

@Serializable data object BrowseHome : NavKey

@Serializable data object TransfersHome : NavKey

@Serializable data object SettingsHome : NavKey

/** Add a server: SMB or SFTP. */
@Serializable data object AddServer : NavKey

/** A server's details: a new one of [kind], or the saved one called [serverId]. */
@Serializable data class ServerForm(val kind: Server.Kind, val serverId: String? = null) : NavKey

/** One job, in detail. */
@Serializable data class JobDetail(val id: Long) : NavKey

/** Tansu's frame: the current place's screens above the navigation bar. */
@Composable
fun TansuApp(modifier: Modifier = Modifier) {
    var place by rememberSaveable { mutableStateOf(Place.Browse) }
    val browse = rememberNavBackStack(BrowseHome)
    val transfers = rememberNavBackStack(TransfersHome)
    val settings = rememberNavBackStack(SettingsHome)
    val backStack =
        when (place) {
            Place.Browse -> browse
            Place.Transfers -> transfers
            Place.Settings -> settings
        }

    val decorators: List<NavEntryDecorator<NavKey>> =
        listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        )
    val screens = screensFor(browse, transfers, onOpenTransfers = { place = Place.Transfers })
    // Every history is decorated all the time, so a place out of sight keeps its screens' state.
    val browseEntries = rememberDecoratedNavEntries(browse, decorators, screens)
    val transfersEntries = rememberDecoratedNavEntries(transfers, decorators, screens)
    val settingsEntries = rememberDecoratedNavEntries(settings, decorators, screens)
    // Browse sits under the other places, so back from their first screen goes to Browse.
    val entries =
        when (place) {
            Place.Browse -> browseEntries
            Place.Transfers -> browseEntries + transfersEntries
            Place.Settings -> browseEntries + settingsEntries
        }

    // Jobs still to finish, counted on the Transfers tab.
    val jobs by appContainer().jobs.all.collectAsStateWithLifecycle()
    val toDo = jobs.count { it.status == Job.Status.Running || it.status == Job.Status.Queued }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        // The bar is for moving between places; a task opened from one has the screen to itself.
        bottomBar = {
            if (backStack.size == 1) {
                TansuNavigationBar(current = place, toDo = toDo, onSelect = { place = it })
            }
        },
    ) { padding ->
        NavDisplay(
            entries = entries,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            onBack = {
                if (backStack.size > 1) backStack.removeLastOrNull() else place = Place.Browse
            },
            transitionSpec = { fade() },
            popTransitionSpec = { fade() },
            predictivePopTransitionSpec = { fade() },
        )
    }
}

/** What each key shows. Browse's tasks, like adding a server, go on top of [browse]. */
private fun screensFor(
    browse: NavBackStack<NavKey>,
    transfers: NavBackStack<NavKey>,
    onOpenTransfers: () -> Unit,
) = entryProvider {
    entry<BrowseHome> {
        BrowseRoute(
            onAddServer = { browse.add(AddServer) },
            onEditServer = { drawer ->
                val kind = if (drawer.kind == Drawer.Kind.Smb) Server.Kind.Smb else Server.Kind.Sftp
                browse.add(ServerForm(kind, drawer.id))
            },
            onOpenTransfers = onOpenTransfers,
        )
    }
    entry<AddServer> {
        AddServerScreen(
            onPick = { kind -> browse.add(ServerForm(kind)) },
            onBack = { browse.removeLastOrNull() },
        )
    }
    entry<ServerForm> { key ->
        ServerFormRoute(
            kind = key.kind,
            serverId = key.serverId,
            // Saved: back to Browse, where a new server's drawer opens.
            onDone = { browse.retainAll(listOf(BrowseHome)) },
            onBack = { browse.removeLastOrNull() },
        )
    }
    entry<TransfersHome> { TransfersRoute(onOpenJob = { id -> transfers.add(JobDetail(id)) }) }
    entry<JobDetail> { key -> JobRoute(id = key.id, onBack = { transfers.removeLastOrNull() }) }
    entry<SettingsHome> { SettingsScreen() }
}

/** Screens change with a short, calm crossfade. */
private fun fade(): ContentTransform =
    fadeIn(tween(SCREEN_MILLIS)) togetherWith fadeOut(tween(SCREEN_MILLIS))

private const val SCREEN_MILLIS = 250

@Composable
private fun TansuNavigationBar(
    current: Place,
    toDo: Int,
    onSelect: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors =
        NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    NavigationBar(modifier = modifier) {
        Place.entries.forEach { place ->
            NavigationBarItem(
                selected = place == current,
                onClick = { onSelect(place) },
                icon = {
                    if (place == Place.Transfers && toDo > 0) {
                        val badge = stringResource(R.string.transfers_badge, toDo)
                        BadgedBox(
                            badge = {
                                Badge(
                                    modifier = Modifier.semantics { contentDescription = badge }
                                ) {
                                    Text(toDo.toString())
                                }
                            }
                        ) {
                            Icon(imageVector = place.icon, contentDescription = null)
                        }
                    } else {
                        Icon(imageVector = place.icon, contentDescription = null)
                    }
                },
                label = { Text(text = stringResource(place.label)) },
                colors = colors,
            )
        }
    }
}
