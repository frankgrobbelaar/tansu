package io.github.frankgrobbelaar.tansu

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Tansu's own choices, kept on the phone. */
class TansuSettings(private val store: DataStore<Preferences>) {

    /** Whether the first-run screens have been seen, so they don't come back. */
    val welcomeDone: Flow<Boolean> = store.data.map { it[WELCOME_DONE] ?: false }

    /** Whether Browse's offer to add a first server was turned down. */
    val serverOfferDismissed: Flow<Boolean> = store.data.map { it[SERVER_OFFER_DISMISSED] ?: false }

    suspend fun finishWelcome() {
        store.edit { it[WELCOME_DONE] = true }
    }

    suspend fun dismissServerOffer() {
        store.edit { it[SERVER_OFFER_DISMISSED] = true }
    }

    private companion object {
        val WELCOME_DONE = booleanPreferencesKey("welcome_done")
        val SERVER_OFFER_DISMISSED = booleanPreferencesKey("server_offer_dismissed")
    }
}
