package io.github.frankgrobbelaar.tansu.files

import io.github.frankgrobbelaar.tansu.servers.Server
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Every drawer: the phone, then each server in the order it was added. A server keeps its file
 * system (and so its connection) until the server is edited or removed.
 */
class Drawers(
    private val phone: Drawer,
    servers: Flow<List<Server>>,
    private val fileSystemFor: (Server) -> FileSystem,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val open = mutableMapOf<String, Pair<Server, FileSystem>>()

    val all: StateFlow<List<Drawer>> =
        servers
            .map { list ->
                closeAllBut(list)
                listOf(phone) + list.map(::drawerOf)
            }
            .stateIn(scope, SharingStarted.Eagerly, listOf(phone))

    private fun drawerOf(server: Server): Drawer {
        val current = open[server.id]
        val files =
            if (current?.first == server) current.second
            else {
                current?.second?.let(::closeLater)
                fileSystemFor(server).also { open[server.id] = server to it }
            }
        val kind = if (server.kind == Server.Kind.Smb) Drawer.Kind.Smb else Drawer.Kind.Sftp
        return Drawer(server.id, server.name, kind, server.root, files, server)
    }

    private fun closeAllBut(servers: List<Server>) {
        val kept = servers.map { it.id }.toSet()
        open.keys
            .filterNot { it in kept }
            .forEach { id -> open.remove(id)?.second?.let(::closeLater) }
    }

    /** Hangs up in the background: saying goodbye to a server is network work. */
    private fun closeLater(files: FileSystem) {
        val closeable = files as? Closeable ?: return
        scope.launch(io) {
            try {
                closeable.close()
            } catch (_: IOException) {
                // A connection that's already gone is closed enough.
            }
        }
    }
}
