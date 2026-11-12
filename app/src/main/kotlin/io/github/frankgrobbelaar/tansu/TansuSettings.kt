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

    suspend fun finishWelcome() {
        store.edit { it[WELCOME_DONE] = true }
    }

    private companion object {
        val WELCOME_DONE = booleanPreferencesKey("welcome_done")
    }
}
