package io.github.frankgrobbelaar.tansu

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Light or dark: as the phone is, or always one of them. */
enum class Theme {
    System,
    Light,
    Dark,
}

/** What Settings lets you choose. */
data class Choices(
    val theme: Theme = Theme.System,
    /** The wallpaper's colours instead of Kiri's. */
    val dynamicColor: Boolean = false,
    /** Files whose names start with a dot, shown in Browse. */
    val showHidden: Boolean = false,
    /** Asking GitHub once a day for a new version. */
    val checkUpdates: Boolean = true,
    /** When tansu last asked GitHub, in milliseconds since the epoch; 0 if never. */
    val lastUpdateCheck: Long = 0,
)

/** Tansu's own choices, kept on the phone. */
class TansuSettings(private val store: DataStore<Preferences>) {

    val choices: Flow<Choices> =
        store.data
            .map { prefs ->
                Choices(
                    theme = Theme.entries.find { it.name == prefs[THEME] } ?: Theme.System,
                    dynamicColor = prefs[DYNAMIC_COLOR] ?: false,
                    showHidden = prefs[SHOW_HIDDEN] ?: false,
                    checkUpdates = prefs[CHECK_UPDATES] ?: true,
                    lastUpdateCheck = prefs[LAST_UPDATE_CHECK] ?: 0,
                )
            }
            .distinctUntilChanged()

    val showHidden: Flow<Boolean> = choices.map { it.showHidden }.distinctUntilChanged()

    suspend fun setTheme(theme: Theme) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setDynamicColor(on: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = on }
    }

    suspend fun setShowHidden(on: Boolean) {
        store.edit { it[SHOW_HIDDEN] = on }
    }

    suspend fun setCheckUpdates(on: Boolean) {
        store.edit { it[CHECK_UPDATES] = on }
    }

    suspend fun updateChecked(at: Long) {
        store.edit { it[LAST_UPDATE_CHECK] = at }
    }

    /** Whether the first-run screens have been seen, so they don't come back. */
    val welcomeDone: Flow<Boolean> = store.data.map { it[WELCOME_DONE] ?: false }

    /** Whether Browse's offer to add a first server was turned down. */
    val serverOfferDismissed: Flow<Boolean> = store.data.map { it[SERVER_OFFER_DISMISSED] ?: false }

    /** The drawer and folder shared files were last saved to, where Save to starts next time. */
    val lastSave: Flow<Pair<String, String>?> =
        store.data.map { prefs ->
            val drawer = prefs[LAST_SAVE_DRAWER] ?: return@map null
            drawer to (prefs[LAST_SAVE_FOLDER] ?: "")
        }

    suspend fun rememberSave(drawerId: String, folder: String) {
        store.edit {
            it[LAST_SAVE_DRAWER] = drawerId
            it[LAST_SAVE_FOLDER] = folder
        }
    }

    suspend fun finishWelcome() {
        store.edit { it[WELCOME_DONE] = true }
    }

    suspend fun dismissServerOffer() {
        store.edit { it[SERVER_OFFER_DISMISSED] = true }
    }

    private companion object {
        val WELCOME_DONE = booleanPreferencesKey("welcome_done")
        val SERVER_OFFER_DISMISSED = booleanPreferencesKey("server_offer_dismissed")
        val LAST_SAVE_DRAWER = stringPreferencesKey("last_save_drawer")
        val LAST_SAVE_FOLDER = stringPreferencesKey("last_save_folder")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }
}
