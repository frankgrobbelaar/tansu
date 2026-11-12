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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.browse.BrowseRoute
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.settings.SettingsScreen
import io.github.frankgrobbelaar.tansu.ui.transfers.TransfersScreen
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
    val screens = entryProvider {
        entry<BrowseHome> { BrowseRoute() }
        entry<TransfersHome> { TransfersScreen() }
        entry<SettingsHome> { SettingsScreen() }
    }
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

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = { TansuNavigationBar(current = place, onSelect = { place = it }) },
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

/** Screens change with a short, calm crossfade. */
private fun fade(): ContentTransform =
    fadeIn(tween(SCREEN_MILLIS)) togetherWith fadeOut(tween(SCREEN_MILLIS))

private const val SCREEN_MILLIS = 250

@Composable
private fun TansuNavigationBar(
    current: Place,
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
                icon = { Icon(imageVector = place.icon, contentDescription = null) },
                label = { Text(text = stringResource(place.label)) },
                colors = colors,
            )
        }
    }
}
