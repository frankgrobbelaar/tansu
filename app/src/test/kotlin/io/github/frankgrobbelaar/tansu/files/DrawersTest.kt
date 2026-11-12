package io.github.frankgrobbelaar.tansu.files

import io.github.frankgrobbelaar.tansu.servers.Server
import java.io.Closeable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawersTest {
    private class Connection(val server: Server) : FileSystem, Closeable {
        var closed = false

        override suspend fun list(path: String) = emptyList<Entry>()

        override fun close() {
            closed = true
        }
    }

    private val phone = Drawer("phone", "Phone", Drawer.Kind.Phone, "/phone", FakeFileSystem())
    private val nas = Server("nas", "NAS", Server.Kind.Smb, "nas", 445, share = "Media")
    private val home = Server("home", "Home", Server.Kind.Sftp, "home", 22, folder = "srv")

    @Test
    fun `the phone comes first, then each server as a drawer of its kind`() =
        runTest(UnconfinedTestDispatcher()) {
            val drawers = drawersOf(MutableStateFlow(listOf(nas, home)))

            val all = drawers.all.value

            assertEquals(listOf("Phone", "NAS", "Home"), all.map { it.name })
            assertEquals(
                listOf(Drawer.Kind.Phone, Drawer.Kind.Smb, Drawer.Kind.Sftp),
                all.map { it.kind },
            )
            assertEquals("/srv", all[2].root)
        }

    @Test
    fun `a server keeps its connection until it's edited or removed`() =
        runTest(UnconfinedTestDispatcher()) {
            val servers = MutableStateFlow(listOf(nas))
            val drawers = drawersOf(servers)
            val first = drawers.all.value[1].fileSystem as Connection

            servers.value = listOf(nas, home)
            assertSame(first, drawers.all.value[1].fileSystem)
            assertFalse(first.closed)

            servers.value = listOf(nas.copy(host = "nas.tailnet"), home)
            val edited = drawers.all.value[1].fileSystem as Connection
            assertTrue(first.closed)
            assertEquals("nas.tailnet", edited.server.host)

            servers.value = listOf(home)
            assertTrue(edited.closed)
            assertEquals(listOf("Phone", "Home"), drawers.all.value.map { it.name })
        }

    private fun kotlinx.coroutines.test.TestScope.drawersOf(
        servers: MutableStateFlow<List<Server>>
    ) =
        Drawers(
            phone = phone,
            servers = servers,
            fileSystemFor = ::Connection,
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
        )
}
