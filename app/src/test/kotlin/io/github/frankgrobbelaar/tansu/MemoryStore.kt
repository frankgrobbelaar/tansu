package io.github.frankgrobbelaar.tansu

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A DataStore kept in memory, so tests see every write at once. */
class MemoryStore : DataStore<Preferences> {
    private val prefs = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()

    override val data: Flow<Preferences> = prefs

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
        lock.withLock {
            transform(prefs.value).also { prefs.value = it }
        }
}
