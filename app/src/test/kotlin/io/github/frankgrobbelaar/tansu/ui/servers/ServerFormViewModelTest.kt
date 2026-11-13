package io.github.frankgrobbelaar.tansu.ui.servers

import androidx.lifecycle.SavedStateHandle
import io.github.frankgrobbelaar.tansu.MainDispatcherRule
import io.github.frankgrobbelaar.tansu.MemoryStore
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.HostKey
import io.github.frankgrobbelaar.tansu.servers.ReversingCipher
import io.github.frankgrobbelaar.tansu.servers.Secret
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.servers.ServerCheck
import io.github.frankgrobbelaar.tansu.servers.ServerStore
import io.github.frankgrobbelaar.tansu.servers.Servers
import io.github.frankgrobbelaar.tansu.servers.StoredSecrets
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerFormViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val servers =
        Servers(ServerStore(MemoryStore()), StoredSecrets(MemoryStore(), ReversingCipher()))
    private val checked = mutableListOf<Pair<Server, Secret>>()
    private var answer: (Server) -> Unit = {}
    private val check = ServerCheck { server, secret ->
        checked += server to secret
        answer(server)
    }

    private fun form(kind: Server.Kind, serverId: String? = null) =
        ServerFormViewModel(kind, serverId, servers, check, SavedStateHandle())

    private fun ServerFormViewModel.fillSmb() {
        edit { it.copy(name = "NAS", host = "nas", share = "Media", username = "sam") }
        setPassword("oak")
    }

    @Test
    fun `Connect checks the details, then saves the server with its password`() = runTest {
        val form = form(Server.Kind.Smb)
        assertFalse(form.state.value.canConnect)

        form.fillSmb()
        assertTrue(form.state.value.canConnect)
        form.connect()

        assertTrue(form.state.value.done)
        val saved = servers.all.first().single()
        assertEquals(listOf("nas", "Media", "sam"), listOf(saved.host, saved.share, saved.username))
        assertEquals(445, saved.port)
        assertEquals("oak", servers.secret(saved.id).password)
        assertEquals(saved, checked.single().first)
    }

    @Test
    fun `an SMB host can carry its own port`() = runTest {
        val form = form(Server.Kind.Smb)
        form.fillSmb()
        form.edit { it.copy(host = "192.168.1.5:4445") }

        form.connect()

        val saved = servers.all.first().single()
        assertEquals("192.168.1.5" to 4445, saved.host to saved.port)
    }

    @Test
    fun `a refused login says so and saves nothing`() = runTest {
        answer = { throw FileSystemException.AuthFailed("nas") }
        val form = form(Server.Kind.Smb)
        form.fillSmb()

        form.connect()

        assertEquals(ServerFormUiState.Failure.LoginRefused, form.state.value.failure)
        assertEquals(ServerFormUiState.Step.Filling, form.state.value.step)
        assertFalse(form.state.value.done)
        assertTrue(servers.all.first().isEmpty())
    }

    @Test
    fun `a guest needs no username or password`() = runTest {
        val form = form(Server.Kind.Smb)
        form.edit { it.copy(host = "nas", share = "Public", signIn = Server.SignIn.Guest) }

        form.connect()

        val saved = servers.all.first().single()
        assertEquals(Server.SignIn.Guest, saved.signIn)
        assertEquals("nas", saved.name)
    }

    @Test
    fun `the first SFTP connection asks to trust the server's key, then pins it`() = runTest {
        val key = HostKey("ED25519", "SHA256:abc")
        answer = { server ->
            if (server.hostKey != key.fingerprint) throw FileSystemException.HostKeyUnknown(key)
        }
        val form = form(Server.Kind.Sftp)
        form.edit { it.copy(host = "home-server", username = "sam") }
        form.setPassword("elm")

        form.connect()
        assertEquals(
            ServerFormUiState.Step.Trust(key, replaces = "", host = "home-server", port = 22),
            form.state.value.step,
        )
        assertTrue(servers.all.first().isEmpty())

        form.trust()
        assertTrue(form.state.value.done)
        assertEquals("SHA256:abc", servers.all.first().single().hostKey)
    }

    @Test
    fun `editing keeps the server's id and key, unless its address changes`() = runTest {
        val home =
            Server(
                "home",
                "Home",
                Server.Kind.Sftp,
                "home-server",
                22,
                username = "sam",
                hostKey = "SHA256:abc",
            )
        servers.save(home, Secret(password = "elm"))
        val form = form(Server.Kind.Sftp, serverId = "home")
        assertEquals("Home", form.state.value.draft.name)
        assertEquals("elm", form.state.value.password)

        form.connect()
        assertEquals(home, checked.last().first)

        form.edit { it.copy(host = "home.tailnet") }
        form.connect()
        assertEquals("", checked.last().first.hostKey)
        assertEquals("home", checked.last().first.id)
    }

    @Test
    fun `a file that isn't a private key is turned away`() {
        val form = form(Server.Kind.Sftp)
        form.edit { it.copy(signIn = Server.SignIn.Key) }

        form.useKey("photo.jpg", "not a key")
        assertEquals(ServerFormUiState.Failure.NotAKey, form.state.value.failure)
        assertFalse(form.state.value.hasKey)

        form.useKey("id_ed25519", "-----BEGIN OPENSSH PRIVATE KEY-----\nb3Blbg==\n")
        assertTrue(form.state.value.hasKey)
        assertEquals("id_ed25519", form.state.value.draft.keyName)
    }

    @Test
    fun `a share typed with a folder after it starts in that folder`() = runTest {
        val form = form(Server.Kind.Smb)
        form.fillSmb()
        form.edit { it.copy(share = "Media\\Photos", folder = "2026") }

        form.connect()

        val saved = servers.all.first().single()
        assertEquals("Media" to "Photos/2026", saved.share to saved.folder)
    }

    @Test
    fun `a whole SMB address pasted as the share is its share and folder`() = runTest {
        val typed =
            mapOf(
                "\\\\nas\\Media\\Photos" to ("Media" to "Photos"),
                "smb://nas/Media/Photos/" to ("Media" to "Photos"),
                "//nas/Media/Photos" to ("Media" to "Photos"),
                "smb://nas/My%20Photos" to ("My Photos" to ""),
                "smb://nas/My Photos/2026" to ("My Photos" to "2026"),
                // No room for a server's name: just the share.
                "\\\\Media" to ("Media" to ""),
                "//Media" to ("Media" to ""),
            )
        for ((share, expected) in typed) {
            val form = form(Server.Kind.Smb)
            form.fillSmb()
            form.edit { it.copy(share = share) }

            form.connect()

            val saved = checked.last().first
            assertEquals(share, expected, saved.share to saved.folder)
        }
    }

    @Test
    fun `an address with no share in it can't be connected`() {
        val form = form(Server.Kind.Smb)
        form.fillSmb()

        form.edit { it.copy(share = "smb://nas") }

        assertFalse(form.state.value.canConnect)
    }

    @Test
    fun `a server that can't be reached says why, as far as the network said`() = runTest {
        val form = form(Server.Kind.Smb)
        form.fillSmb()
        val causes =
            mapOf(
                UnknownHostException("nas") to ServerFormUiState.Failure.UnknownName,
                ConnectException("Connection refused") to ServerFormUiState.Failure.Refused,
                SocketTimeoutException("connect timed out") to
                    ServerFormUiState.Failure.Unreachable,
            )

        for ((cause, failure) in causes) {
            answer = { throw FileSystemException.Unreachable("nas", IOException(cause)) }
            form.connect()

            assertEquals(failure, form.state.value.failure)
        }
    }

    @Test
    fun `a check given up on can't change the form`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stubborn = ServerCheck { _, _ ->
            withContext(NonCancellable) {
                gate.await()
                throw FileSystemException.HostKeyUnknown(HostKey("ED25519", "SHA256:typo"))
            }
        }
        val form =
            ServerFormViewModel(Server.Kind.Sftp, null, servers, stubborn, SavedStateHandle())
        form.edit { it.copy(host = "typo-host", username = "sam") }

        form.connect()
        form.cancel()
        gate.complete(Unit)

        assertEquals(ServerFormUiState.Step.Filling, form.state.value.step)
    }
}
