package io.github.frankgrobbelaar.tansu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.frankgrobbelaar.tansu.Choices
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.TansuSettings
import io.github.frankgrobbelaar.tansu.Theme
import io.github.frankgrobbelaar.tansu.servers.SavedServers
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.updates.Update
import io.github.frankgrobbelaar.tansu.updates.Updates
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val choices: Choices = Choices(),
    val servers: List<Server> = emptyList(),
    /** The version installed. */
    val version: String = "",
    val update: Update = Update.Unknown,
    /** A downloaded update to hand to Android's installer, once. */
    val install: File? = null,
)

/** What Settings lets you do. */
class SettingsActions(
    val onTheme: (Theme) -> Unit,
    val onDynamicColor: (Boolean) -> Unit,
    val onShowHidden: (Boolean) -> Unit,
    val onCheckUpdates: (Boolean) -> Unit,
    val onCheckNow: () -> Unit,
    val onDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onInstall: () -> Unit,
    val onAddServer: () -> Unit,
    val onEditServer: (Server) -> Unit,
)

/** Settings: how tansu looks and behaves, your servers, and the version you're on. */
class SettingsViewModel(
    private val settings: TansuSettings,
    servers: SavedServers,
    private val updates: Updates,
    version: String,
) : ViewModel() {

    private val install = MutableStateFlow<File?>(null)

    val state: StateFlow<SettingsUiState> =
        combine(settings.choices, servers.all, updates.update, install) {
                choices,
                servers,
                update,
                install ->
                SettingsUiState(choices, servers, version, update, install)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS),
                SettingsUiState(version = version, update = updates.update.value),
            )

    fun setTheme(theme: Theme) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    fun setDynamicColor(on: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(on) }
    }

    fun setShowHidden(on: Boolean) {
        viewModelScope.launch { settings.setShowHidden(on) }
    }

    fun setCheckUpdates(on: Boolean) {
        viewModelScope.launch { settings.setCheckUpdates(on) }
    }

    fun checkNow() = updates.check()

    /** Downloads the update on offer, and has Android install it once it's here and checked. */
    fun downloadAndInstall() {
        updates.download()
        viewModelScope.launch {
            val ended = updates.update.first { it !is Update.Downloading }
            if (ended is Update.Ready) install.value = ended.apk
        }
    }

    fun cancelDownload() = updates.cancel()

    /** Offers the downloaded update to Android's installer again, say after it was turned down. */
    fun install() {
        (updates.update.value as? Update.Ready)?.let { install.value = it.apk }
    }

    fun installHandled() {
        install.value = null
    }

    companion object {
        private const val STOP_AFTER_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as TansuApplication).container
                SettingsViewModel(
                    settings = container.settings,
                    servers = container.servers,
                    updates = container.updates,
                    version = container.version,
                )
            }
        }
    }
}
