package io.github.frankgrobbelaar.tansu.files

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectingTest {
    private var opened = 0
    private val ended = mutableListOf<Int>()
    private val sessions =
        Reconnecting(open = { ++opened }, alive = { true }, end = { ended += it })

    /** A call that finds the connection [dead], as a server that dropped an idle one leaves it. */
    private fun call(dead: Int): (Int) -> Int = { session ->
        if (session == dead) throw FileSystemException.Unreachable("nas")
        session
    }

    @Test
    fun `a reused connection that died is replaced, and the call tried once more`() = runTest {
        sessions.use { it }

        assertEquals(2, sessions.use(block = call(dead = 1)))
        assertEquals(listOf(1), ended)
    }

    @Test
    fun `a call that mustn't happen twice isn't tried again, but the next call reconnects`() =
        runTest {
            sessions.use { it }

            val failed = runCatching { sessions.use(retry = false, block = call(dead = 1)) }
            assertTrue(failed.exceptionOrNull() is FileSystemException.Unreachable)
            assertEquals(listOf(1), ended)
            assertEquals(2, sessions.use { it })
        }

    @Test
    fun `a new connection that fails isn't tried again`() = runTest {
        val failed = runCatching { sessions.use(block = call(dead = 1)) }

        assertTrue(failed.exceptionOrNull() is FileSystemException.Unreachable)
        assertEquals(1, opened)
    }
}
