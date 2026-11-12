package io.github.frankgrobbelaar.tansu.files

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One live connection to a server, opened on first use and reused. When a reused connection turns
 * out to be dead, as servers drop idle ones, it's replaced and the call tried once more; a server
 * that's really gone still fails, once.
 */
internal class Reconnecting<S : Any>(
    private val open: suspend () -> S,
    private val alive: (S) -> Boolean,
    private val end: (S) -> Unit,
) {
    private val lock = Mutex()
    @Volatile private var current: S? = null

    /** Runs [block] on the connection. It throws [FileSystemException.Unreachable] if it's dead. */
    suspend fun <T> use(block: (S) -> T): T {
        val (session, fresh) = acquire()
        return try {
            block(session)
        } catch (e: FileSystemException.Unreachable) {
            drop(session)
            if (fresh) throw e
            val (again, _) = acquire()
            try {
                block(again)
            } catch (gone: FileSystemException.Unreachable) {
                drop(again)
                throw gone
            }
        }
    }

    /**
     * Hangs up, if connected; the next call connects again. It doesn't wait for the lock: a call
     * still connecting keeps its new connection, for a job that's still using this server.
     */
    fun close() {
        val ending = current
        current = null
        ending?.let(end)
    }

    /** The connection, and whether it was just made. */
    private suspend fun acquire(): Pair<S, Boolean> = lock.withLock {
        current?.takeIf(alive)?.let {
            return@withLock it to false
        }
        current?.let(end)
        current = null
        open().also { current = it } to true
    }

    /** Ends [session] if it's still the current connection; a newer one is left alone. */
    private suspend fun drop(session: S) {
        val mine = lock.withLock { (current === session).also { if (it) current = null } }
        if (mine) end(session)
    }
}
