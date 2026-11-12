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
import androidx.navigation3.runtime.NavEntry
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
import io.github.frankgrobbelaar.tansu.ui.settings.SettingsRoute
import io.github.frankgrobbelaar.tansu.ui.transfers.JobRoute
import io.github.frankgrobbelaar.tansu.ui.transfers.TransfersRoute
import io.github.frankgrobbelaar.tansu.updates.Update
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
    val histories =
        Histories(
            browse = rememberNavBackStack(BrowseHome),
            transfers = rememberNavBackStack(TransfersHome),
            settings = rememberNavBackStack(SettingsHome),
        )
    val backStack = histories.of(place)
    val entries = rememberEntries(place, histories, onOpenTransfers = { place = Place.Transfers })

    // Jobs still to finish, counted on the Transfers tab; a new version, marked on Settings.
    val container = appContainer()
    val jobs by container.jobs.all.collectAsStateWithLifecycle()
    val toDo = jobs.count { it.status == Job.Status.Running || it.status == Job.Status.Queued }
    val update by container.updates.update.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        // The bar is for moving between places; a task opened from one has the screen to itself.
        bottomBar = {
            if (backStack.size == 1) {
                TansuNavigationBar(
                    current = place,
                    toDo = toDo,
                    newVersion = update is Update.Available,
                    onSelect = { place = it },
                )
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

/** Each place's history of screens. */
private class Histories(
    val browse: NavBackStack<NavKey>,
    val transfers: NavBackStack<NavKey>,
    val settings: NavBackStack<NavKey>,
) {
    fun of(place: Place) =
        when (place) {
            Place.Browse -> browse
            Place.Transfers -> transfers
            Place.Settings -> settings
        }
}

/**
 * The screens to show in [place]. Every history is decorated all the time, so a place out of sight
 * keeps its screens' state, and Browse sits under the other places, so back from their first screen
 * goes to Browse.
 */
@Composable
private fun rememberEntries(
    place: Place,
    histories: Histories,
    onOpenTransfers: () -> Unit,
): List<NavEntry<NavKey>> {
    val decorators: List<NavEntryDecorator<NavKey>> =
        listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        )
    val browse = histories.browse
    val browseEntries =
        rememberDecoratedNavEntries(
            browse,
            decorators,
            screensFor(Place.Browse, browse, onOpenTransfers),
        )
    val transfers = histories.transfers
    val transfersEntries =
        rememberDecoratedNavEntries(
            transfers,
            decorators,
            screensFor(Place.Transfers, transfers, onOpenTransfers),
        )
    val settings = histories.settings
    val settingsEntries =
        rememberDecoratedNavEntries(
            settings,
            decorators,
            screensFor(Place.Settings, settings, onOpenTransfers),
        )
    return when (place) {
        Place.Browse -> browseEntries
        Place.Transfers -> browseEntries + transfersEntries
        Place.Settings -> browseEntries + settingsEntries
    }
}

/** What each key shows in [place]. Its tasks, like adding a server, go on top of its [history]. */
private fun screensFor(
    place: Place,
    history: NavBackStack<NavKey>,
    onOpenTransfers: () -> Unit,
) = entryProvider {
    // A task can be open in two places at once, like Add a server; each keeps its own state.
    val keyIn: (NavKey) -> Any = { "${place.name}/$it" }
    entry<BrowseHome>(keyIn) {
        BrowseRoute(
            onAddServer = { history.add(AddServer) },
            onEditServer = { drawer ->
                val kind = if (drawer.kind == Drawer.Kind.Smb) Server.Kind.Smb else Server.Kind.Sftp
                history.add(ServerForm(kind, drawer.id))
            },
            onOpenTransfers = onOpenTransfers,
        )
    }
    entry<AddServer>(keyIn) {
        AddServerScreen(
            onPick = { kind -> history.add(ServerForm(kind)) },
            onBack = { history.removeLastOrNull() },
        )
    }
    entry<ServerForm>(keyIn) { key ->
        ServerFormRoute(
            kind = key.kind,
            serverId = key.serverId,
            // Saved: back to the place's first screen. Browse opens a new server's drawer.
            onDone = { history.retainAll(listOf(history.first())) },
            onBack = { history.removeLastOrNull() },
        )
    }
    entry<TransfersHome>(keyIn) { TransfersRoute(onOpenJob = { id -> history.add(JobDetail(id)) }) }
    entry<JobDetail>(keyIn) { key ->
        JobRoute(id = key.id, onBack = { history.removeLastOrNull() })
    }
    entry<SettingsHome>(keyIn) {
        SettingsRoute(
            onAddServer = { history.add(AddServer) },
            onEditServer = { server -> history.add(ServerForm(server.kind, server.id)) },
        )
    }
}

/** Screens change with a short, calm crossfade. */
private fun fade(): ContentTransform =
    fadeIn(tween(SCREEN_MILLIS)) togetherWith fadeOut(tween(SCREEN_MILLIS))

private const val SCREEN_MILLIS = 250

@Composable
private fun TansuNavigationBar(
    current: Place,
    toDo: Int,
    newVersion: Boolean,
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
                    when {
                        place == Place.Transfers && toDo > 0 ->
                            Badged(
                                icon = place.icon,
                                description = stringResource(R.string.transfers_badge, toDo),
                                count = toDo.toString(),
                            )
                        place == Place.Settings && newVersion ->
                            Badged(
                                icon = place.icon,
                                description = stringResource(R.string.updates_badge),
                            )
                        else -> Icon(imageVector = place.icon, contentDescription = null)
                    }
                },
                label = { Text(text = stringResource(place.label)) },
                colors = colors,
            )
        }
    }
}

/** A place's icon with a badge: a count, or a dot when [count] is null. */
@Composable
private fun Badged(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    count: String? = null,
) {
    BadgedBox(
        modifier = modifier,
        badge = {
            val said = Modifier.semantics { contentDescription = description }
            if (count != null) Badge(modifier = said) { Text(count) } else Badge(modifier = said)
        },
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}
