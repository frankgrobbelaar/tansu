package io.github.frankgrobbelaar.tansu.servers

import io.github.frankgrobbelaar.tansu.MemoryStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServersTest {
    private val secretStore = MemoryStore()
    private val secrets = StoredSecrets(secretStore, ReversingCipher())
    private val servers = Servers(ServerStore(MemoryStore()), secrets)

    private val nas =
        Server("nas", "NAS", Server.Kind.Smb, "nas", 445, share = "Media", username = "sam")
    private val home = Server("home", "Home server", Server.Kind.Sftp, "home-server", 22)

    @Test
    fun `servers keep the order they were added in, and an edit stays in place`() = runTest {
        servers.save(nas, Secret(password = "oak"))
        servers.save(home, Secret(password = "elm"))
        servers.save(nas.copy(name = "Media box"), Secret(password = "ash"))

        assertEquals(listOf("Media box", "Home server"), servers.all.first().map { it.name })
        assertEquals("ash", servers.secret("nas").password)
    }

    @Test
    fun `a secret is stored sealed, never as it was typed`() = runTest {
        servers.save(nas, Secret(password = "oak-drawer-42"))

        val stored = secretStore.data.first().asMap().values.single() as String

        assertFalse("oak-drawer-42" in stored)
        assertEquals("oak-drawer-42", servers.secret("nas").password)
    }

    @Test
    fun `removing a server forgets its secret too`() = runTest {
        servers.save(nas, Secret(password = "oak"))

        servers.remove("nas")

        assertEquals(emptyList<Server>(), servers.all.first())
        assertEquals(Secret(), servers.secret("nas"))
        assertNull(servers.find("nas"))
    }

    @Test
    fun `a new server is announced once, an edited one isn't`() =
        runTest(UnconfinedTestDispatcher()) {
            val added = async { servers.added.first() }
            servers.save(nas, Secret())
            servers.save(nas.copy(name = "NAS 2"), Secret())

            assertEquals("NAS", added.await().name)
        }

    @Test
    fun `trusting a key pins it on the server`() = runTest {
        servers.save(home, Secret(password = "elm"))

        servers.trustKey("home", "SHA256:abc")

        assertEquals("SHA256:abc", servers.find("home")?.hostKey)
    }

    @Test
    fun `using another share changes only the share`() = runTest {
        servers.save(nas.copy(folder = "Photos"), Secret(password = "oak"))

        servers.useShare("nas", "Media 2026")

        assertEquals(nas.copy(share = "Media 2026", folder = "Photos"), servers.find("nas"))
        assertEquals("oak", servers.secret("nas").password)
    }
}
