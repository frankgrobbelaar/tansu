package io.github.frankgrobbelaar.tansu.files

import io.github.frankgrobbelaar.tansu.servers.Secret
import io.github.frankgrobbelaar.tansu.servers.Secrets
import io.github.frankgrobbelaar.tansu.servers.Server
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SftpFileSystem against a real SSH server, Apache MINA's, running in the test. */
class SftpFileSystemTest {
    @get:Rule val folder = TemporaryFolder()

    private val hostKeys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.genKeyPair()
    private val hostKey = hostKeyOf(hostKeys.public)
    private lateinit var sshd: SshServer

    private class OneSecret(val secret: Secret) : Secrets {
        override suspend fun read(serverId: String) = secret

        override suspend fun write(serverId: String, secret: Secret) = Unit

        override suspend fun forget(serverId: String) = Unit
    }

    @Before
    fun startServer() {
        File(folder.root, "notes.txt").writeText("hello")
        File(folder.root, "photos").mkdir()
        sshd =
            SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                port = 0
                keyPairProvider = KeyPairProvider.wrap(hostKeys)
                passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                    user == "sam" && password == "elm"
                }
                publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                    user == "sam" && hostKeyOf(key).fingerprint == CLIENT_FINGERPRINT
                }
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(folder.root.toPath())
                start()
            }
    }

    @After
    fun stopServer() {
        sshd.stop(true)
    }

    private fun sftp(
        pinned: String = hostKey.fingerprint,
        secret: Secret = Secret(password = "elm"),
        signIn: Server.SignIn = Server.SignIn.Password,
    ) =
        SftpFileSystem(
            Server(
                id = "home",
                name = "Home",
                kind = Server.Kind.Sftp,
                host = "127.0.0.1",
                port = sshd.port,
                username = "sam",
                signIn = signIn,
                hostKey = pinned,
            ),
            OneSecret(secret),
        )

    /** Lists [path], expecting [T] to be thrown. */
    private inline fun <reified T : FileSystemException> refusal(
        files: SftpFileSystem,
        path: String = "/",
    ): T = assertThrows(T::class.java) { runBlocking { files.list(path) } }

    @Test
    fun `lists a folder once the server's key is the one trusted`() {
        sftp().use { files ->
            val entries = runBlocking { files.list("/") }.associateBy { it.name }

            assertEquals(setOf("notes.txt", "photos"), entries.keys)
            assertEquals(5L, entries.getValue("notes.txt").size)
            assertEquals("/photos", entries.getValue("photos").path)
            assertTrue(entries.getValue("photos").isFolder)
        }
    }

    @Test
    fun `a server seen for the first time is refused, showing its key`() {
        sftp(pinned = "").use { files ->
            val refused = refusal<FileSystemException.HostKeyUnknown>(files)

            assertEquals(hostKey, refused.key)
            assertEquals("ECDSA", refused.key.type)
        }
    }

    @Test
    fun `a key other than the trusted one blocks the connection`() {
        sftp(pinned = "SHA256:somethingElse").use { files ->
            val refused = refusal<FileSystemException.HostKeyChanged>(files)

            assertEquals("SHA256:somethingElse", refused.trusted)
            assertEquals(hostKey, refused.presented)
        }
    }

    @Test
    fun `a wrong password is turned down`() {
        sftp(secret = Secret(password = "oak")).use { files ->
            refusal<FileSystemException.AuthFailed>(files)
        }
    }

    @Test
    fun `a folder that isn't there says so`() {
        sftp().use { files -> refusal<FileSystemException.NotFound>(files, "/gone") }
    }

    @Test
    fun `a private key signs in, and a file that isn't one can't be read`() {
        sftp(secret = Secret(privateKey = CLIENT_KEY), signIn = Server.SignIn.Key).use { files ->
            assertEquals(2, runBlocking { files.list("/") }.size)
        }
        val garbled = CLIENT_KEY.replace("b3BlbnNzaC1rZXktdjEA", "AAAA")
        sftp(secret = Secret(privateKey = garbled), signIn = Server.SignIn.Key).use { files ->
            refusal<FileSystemException.BadKey>(files)
        }
    }

    @Test
    fun `fingerprints read the way ssh-keygen prints them`() {
        val blob = Base64.getDecoder().decode(CLIENT_PUBLIC_KEY)
        val key = Buffer.PlainBuffer(blob).readPublicKey()

        assertEquals(HostKey("ED25519", CLIENT_FINGERPRINT), hostKeyOf(key))
    }

    private companion object {
        // A throwaway key made for this test with `ssh-keygen -t ed25519`; it guards nothing.
        val CLIENT_KEY =
            listOf(
                    "-----BEGIN OPENSSH PRIVATE KEY-----",
                    "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW",
                    "QyNTUxOQAAACDk4vVgms1qvcRjV/yrUZRNzGVOiUVXCNuAFRc+/0c/jAAAAIhjcA21Y3AN",
                    "tQAAAAtzc2gtZWQyNTUxOQAAACDk4vVgms1qvcRjV/yrUZRNzGVOiUVXCNuAFRc+/0c/jA",
                    "AAAEDlEVj44bzkO0RFcuRy/Wi5A8DMlGoWADYZo8XIbmx77uTi9WCazWq9xGNX/KtRlE3M",
                    "ZU6JRVcI24AVFz7/Rz+MAAAAAAECAwQF",
                    "-----END OPENSSH PRIVATE KEY-----",
                )
                .joinToString("\n", postfix = "\n")
        const val CLIENT_PUBLIC_KEY =
            "AAAAC3NzaC1lZDI1NTE5AAAAIOTi9WCazWq9xGNX/KtRlE3MZU6JRVcI24AVFz7/Rz+M"
        // What `ssh-keygen -lf` prints for that key.
        const val CLIENT_FINGERPRINT = "SHA256:3+YdfCd7VxYj6vTHGfSg8y/dauFOlslggxoxhQQNZgw"
    }
}
