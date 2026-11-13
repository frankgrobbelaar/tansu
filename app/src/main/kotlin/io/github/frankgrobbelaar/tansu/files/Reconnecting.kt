package io.github.frankgrobbelaar.tansu.files

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One live connection to a server, opened on first use and reused. A connection that fails at once,
 * as one does while Wi-Fi or Tailscale wakes up, is tried again for a few seconds. When a call
 * finds the connection dead, as servers drop idle ones, or gets no answer in time, as from a server
 * whose disks are spinning up, it's tried once more on a new connection, unless it mustn't be.
 */
internal class Reconnecting<S : Any>(
    private val open: suspend () -> S,
    private val alive: (S) -> Boolean,
    private val end: (S) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    @Volatile private var current: S? = null

    /** How many tries to connect have ended, and how the last one failed, if it did. */
    @Volatile private var tries = 0L
    private var failed: FileSystemException? = null

    /**
     * Runs [block] on the connection. It throws [FileSystemException.Unreachable] if it's dead. A
     * call that mustn't happen twice, such as a rename, isn't tried again unless [retry]: the
     * connection may have died after it went through, with only its answer lost.
     */
    suspend fun <T> use(retry: Boolean = true, block: (S) -> T): T {
        val session = acquire()
        return try {
            block(session)
        } catch (e: FileSystemException.Unreachable) {
            withContext(NonCancellable) { drop(session) }
            if (!retry) throw e
            currentCoroutineContext().ensureActive()
            val again = acquire()
            try {
                block(again)
            } catch (gone: FileSystemException.Unreachable) {
                withContext(NonCancellable) { drop(again) }
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

    /**
     * The connection, made if need be. Calls that waited while a try to connect failed fail with
     * it, rather than each trying again, as a grid of pictures would, one after another.
     */
    private suspend fun acquire(): S {
        val seen = tries
        return lock.withLock {
            current?.takeIf(alive)?.let {
                return@withLock it
            }
            if (tries != seen) failed?.let { throw it }
            current?.let(end)
            current = null
            failed = null
            try {
                connect().also { current = it }
            } catch (e: FileSystemException) {
                failed = e
                throw e
            } finally {
                tries++
            }
        }
    }

    /**
     * A new connection. One that fails at once is tried again, a little later each time, for a few
     * seconds; one that waited out its whole timeout has been given its time, and one turned away
     * has had its answer.
     */
    private suspend fun connect(): S {
        val started = clock()
        var wait = FIRST_PAUSE_MILLIS
        while (true) {
            try {
                return open()
            } catch (e: FileSystemException.Unreachable) {
                val refused = e.why == FileSystemException.Unreachable.Why.Refused
                if (refused || clock() - started + wait > PATIENCE_MILLIS) throw e
            }
            pause(wait)
            wait *= 2
        }
    }

    /**
     * Ends [session] if it's still the current connection; a newer one is left alone. It's ended
     * before anyone else can connect, so they can't be handed it again.
     */
    private suspend fun drop(session: S) {
        lock.withLock {
            if (current === session) {
                current = null
                end(session)
            }
        }
    }

    companion object {
        /** How long a connection that fails at once is tried again for. */
        const val PATIENCE_MILLIS = 10_000L
        private const val FIRST_PAUSE_MILLIS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
