package io.github.frankgrobbelaar.tansu.files

import java.net.ConnectException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectingTest {
    private var now = 0L
    private val pauses = mutableListOf<Long>()
    private var opened = 0
    private val ended = mutableListOf<Int>()

    /** Connections numbered as they're made; [failing] says how each attempt to open one goes. */
    private fun sessions(failing: (attempt: Int) -> Exception? = { null }) =
        Reconnecting(
            open = {
                opened++
                failing(opened)?.let { throw it }
                opened
            },
            alive = { true },
            end = { ended += it },
            clock = { now },
            pause = {
                pauses += it
                now += it
            },
        )

    /** A call that finds the connection [dead], as a server that dropped an idle one leaves it. */
    private fun call(dead: Int): (Int) -> Int = { session ->
        if (session == dead) throw FileSystemException.Unreachable("nas")
        session
    }

    @Test
    fun `a reused connection that died is replaced, and the call tried once more`() = runTest {
        val sessions = sessions()
        sessions.use { it }

        assertEquals(2, sessions.use(block = call(dead = 1)))
        assertEquals(listOf(1), ended)
    }

    @Test
    fun `a call that mustn't happen twice isn't tried again, but the next call reconnects`() =
        runTest {
            val sessions = sessions()
            sessions.use { it }

            val failed = runCatching { sessions.use(retry = false, block = call(dead = 1)) }
            assertTrue(failed.exceptionOrNull() is FileSystemException.Unreachable)
            assertEquals(listOf(1), ended)
            assertEquals(2, sessions.use { it })
        }

    @Test
    fun `a call a new connection gets no answer to is tried once more, on another`() = runTest {
        // As a server whose disks were asleep answers the second time.
        val sessions = sessions()

        assertEquals(2, sessions.use(block = call(dead = 1)))
        assertEquals(listOf(1), ended)
    }

    @Test
    fun `a call that fails on two connections fails`() = runTest {
        val sessions = sessions()

        val failed = runCatching { sessions.use { throw FileSystemException.Unreachable("nas") } }

        assertTrue(failed.exceptionOrNull() is FileSystemException.Unreachable)
        assertEquals(listOf(1, 2), ended)
    }

    @Test
    fun `a connection turned away at once is tried again, a little later each time`() = runTest {
        // As while Tailscale or the Wi-Fi wakes up: the name or the route isn't there yet.
        val sessions = sessions { attempt ->
            if (attempt < 3) FileSystemException.Unreachable("nas") else null
        }

        assertEquals(3, sessions.use { it })
        assertEquals(listOf(1_000L, 2_000L), pauses)
    }

    @Test
    fun `calls that waited while a connection failed fail with it, not each trying again`() =
        runTest {
            // As a grid of pictures, all waiting on a server that's off.
            val sessions =
                Reconnecting(
                    open = {
                        opened++
                        delay(CONNECT_TIMEOUT_MILLIS)
                        now += CONNECT_TIMEOUT_MILLIS
                        throw FileSystemException.Unreachable("nas")
                    },
                    alive = { true },
                    end = {},
                    clock = { now },
                    pause = { now += it },
                )

            val calls = List(3) { async { runCatching { sessions.use { it } } } }

            assertTrue(
                calls.awaitAll().all { it.exceptionOrNull() is FileSystemException.Unreachable }
            )
            assertEquals(1, opened)
            // The next call, after they all failed, tries again.
            runCatching { sessions.use { it } }
            assertEquals(2, opened)
        }

    @Test
    fun `a new folder whose answer was lost is made once`() = runTest {
        val files = MemoryFileSystem()
        var tries = 0

        // Made, but its answer lost: it's there, so it isn't made again.
        files.madeOnce("/Trip") {
            tries++
            files.createFolder("/Trip")
            throw FileSystemException.Unreachable("nas")
        }
        assertEquals(1, tries)

        // Lost before it was made: made once more.
        files.madeOnce("/Walk") {
            if (++tries == 2) throw FileSystemException.Unreachable("nas")
            files.createFolder("/Walk")
        }
        assertEquals(3, tries)
        assertTrue(files.exists("/Walk"))
    }

    @Test
    fun `a connection that keeps failing at once gives up after a few seconds`() = runTest {
        val sessions = sessions { FileSystemException.Unreachable("nas") }

        val failed = runCatching { sessions.use { it } }

        assertTrue(failed.exceptionOrNull() is FileSystemException.Unreachable)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), pauses)
        assertTrue(now <= Reconnecting.PATIENCE_MILLIS)
    }

    @Test
    fun `a connection that waited out its timeout isn't tried again`() = runTest {
        val sessions = sessions {
            now += CONNECT_TIMEOUT_MILLIS
            FileSystemException.Unreachable("nas")
        }

        runCatching { sessions.use { it } }

        assertEquals(1, opened)
    }

    @Test
    fun `a connection the server turned away isn't tried again`() = runTest {
        val refused = ConnectException("Connection refused")
        val sessions = sessions { FileSystemException.Unreachable("nas", refused) }

        runCatching { sessions.use { it } }

        assertEquals(1, opened)
    }

    @Test
    fun `a login turned down isn't tried again`() = runTest {
        val sessions = sessions { FileSystemException.AuthFailed("nas") }

        val failed = runCatching { sessions.use { it } }

        assertTrue(failed.exceptionOrNull() is FileSystemException.AuthFailed)
        assertEquals(1, opened)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
    }
}
