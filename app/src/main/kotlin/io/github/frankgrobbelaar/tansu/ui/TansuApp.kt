package io.github.frankgrobbelaar.tansu.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.browse.BrowseScreen
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

    // Back from the root of Transfers or Settings goes to Browse before it leaves the app.
    BackHandler(enabled = place != Place.Browse && backStack.size == 1) { place = Place.Browse }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { TansuNavigationBar(current = place, onSelect = { place = it }) },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(padding),
            onBack = { backStack.removeLastOrNull() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider =
                entryProvider {
                    entry<BrowseHome> { BrowseScreen() }
                    entry<TransfersHome> { TransfersScreen() }
                    entry<SettingsHome> { SettingsScreen() }
                },
        )
    }
}

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
