package io.github.frankgrobbelaar.tansu.servers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** The servers the user added, in the order they were added. Nothing secret is kept here. */
class ServerStore(private val store: DataStore<Preferences>) {

    val servers: Flow<List<Server>> = store.data.map(::decode)

    /** Adds [server], or replaces the one with its id in place. */
    suspend fun save(server: Server) {
        store.edit { prefs ->
            val servers = decode(prefs)
            val index = servers.indexOfFirst { it.id == server.id }
            val updated =
                if (index < 0) servers + server
                else servers.toMutableList().apply { set(index, server) }
            prefs[SERVERS] = json.encodeToString(updated)
        }
    }

    suspend fun remove(id: String) {
        store.edit { prefs ->
            prefs[SERVERS] = json.encodeToString(decode(prefs).filterNot { it.id == id })
        }
    }

    private fun decode(prefs: Preferences): List<Server> =
        prefs[SERVERS]?.let { json.decodeFromString<List<Server>>(it) } ?: emptyList()

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
        val json = Json { ignoreUnknownKeys = true }
    }
}
