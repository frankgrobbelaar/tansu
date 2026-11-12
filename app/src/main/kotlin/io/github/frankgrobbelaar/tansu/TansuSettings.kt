package io.github.frankgrobbelaar.tansu

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Tansu's own choices, kept on the phone. */
class TansuSettings(private val store: DataStore<Preferences>) {

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
    }
}
