package io.github.frankgrobbelaar.tansu.servers

import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.ListsShares
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The saved servers, and the changes Browse makes to them. */
interface SavedServers {
    val all: Flow<List<Server>>

    /** Each server as it's added, so Browse can open its drawer. */
    val added: SharedFlow<Server>

    /** Pins a new host key after the user chose to trust it. */
    suspend fun trustKey(serverId: String, fingerprint: String)

    /**
     * Points an SMB server at [share], one it has, in place of a share that's gone, from its top: a
     * folder in the share that's gone may not be in this one.
     */
    suspend fun useShare(serverId: String, share: String)

    /** Forgets the server and its secret. */
    suspend fun remove(serverId: String)
}

/** Saved servers and their secrets, kept in step. */
class Servers(private val store: ServerStore, private val secrets: Secrets) : SavedServers {

    private val newlyAdded = MutableSharedFlow<Server>(extraBufferCapacity = 1)

    override val all: Flow<List<Server>> = store.servers

    override val added: SharedFlow<Server> = newlyAdded.asSharedFlow()

    suspend fun find(id: String): Server? = store.servers.first().find { it.id == id }

    suspend fun secret(id: String): Secret = secrets.read(id)

    /** Saves [server] with the [secret] that signs in to it, adding it if it's new. */
    suspend fun save(server: Server, secret: Secret) {
        val isNew = find(server.id) == null
        secrets.write(server.id, secret)
        store.save(server)
        if (isNew) newlyAdded.emit(server)
    }

    override suspend fun trustKey(serverId: String, fingerprint: String) {
        store.update(serverId) { it.copy(hostKey = fingerprint) }
    }

    override suspend fun useShare(serverId: String, share: String) {
        store.update(serverId) { it.copy(share = share, folder = "") }
    }

    override suspend fun remove(serverId: String) {
        store.remove(serverId)
        secrets.forget(serverId)
    }
}

/** Checks a server's details work by listing its top folder, before anything is saved. */
fun interface ServerCheck {
    suspend fun check(server: Server, secret: Secret)
}

/** A [ServerCheck] over the real network, on a connection of its own that's closed after. */
class LiveServerCheck(private val fileSystemFor: (Server, Secrets) -> FileSystem) : ServerCheck {
    override suspend fun check(server: Server, secret: Secret) {
        val files = fileSystemFor(server, OneSecret(secret))
        try {
            files.list(server.root)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { (files as? Closeable)?.close() }
        }
    }
}

/** Lists the shares an SMB server has, to offer when the one asked for isn't there. */
fun interface ServerShares {
    /** The shares on [server], signing in with [secret]; none for a server without shares. */
    suspend fun shares(server: Server, secret: Secret): List<String>
}

/**
 * [ServerShares] over the real network. Listing shares hangs up its own connection, so unlike a
 * check there's nothing left to close.
 */
class LiveServerShares(private val fileSystemFor: (Server, Secrets) -> FileSystem) : ServerShares {
    override suspend fun shares(server: Server, secret: Secret): List<String> =
        (fileSystemFor(server, OneSecret(secret)) as? ListsShares)?.shares().orEmpty()
}

/** The one secret being tried while a server is added. */
private class OneSecret(private val secret: Secret) : Secrets {
    override suspend fun read(serverId: String) = secret

    override suspend fun write(serverId: String, secret: Secret) = Unit

    override suspend fun forget(serverId: String) = Unit
}
