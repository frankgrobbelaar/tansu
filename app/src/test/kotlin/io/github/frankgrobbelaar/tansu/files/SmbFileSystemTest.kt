package io.github.frankgrobbelaar.tansu.files

import io.github.frankgrobbelaar.tansu.servers.Secret
import io.github.frankgrobbelaar.tansu.servers.Secrets
import io.github.frankgrobbelaar.tansu.servers.Server
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

/**
 * SmbFileSystem against a real Samba server in Docker. CI always has Docker; without it, as on a
 * laptop that hasn't got it, these tests are skipped.
 */
class SmbFileSystemTest {
    private class OneSecret(val secret: Secret) : Secrets {
        override suspend fun read(serverId: String) = secret

        override suspend fun write(serverId: String, secret: Secret) = Unit

        override suspend fun forget(serverId: String) = Unit
    }

    private fun smb(share: String = "Media", password: String = "oak") =
        SmbFileSystem(
            Server(
                id = "nas",
                name = "NAS",
                kind = Server.Kind.Smb,
                host = samba.host,
                port = samba.getMappedPort(SMB_PORT),
                share = share,
                username = "sam",
            ),
            OneSecret(Secret(password = password)),
        )

    /** Lists [path], expecting [T] to be thrown. */
    private inline fun <reified T : FileSystemException> refusal(
        files: SmbFileSystem,
        path: String = "/",
    ): T = assertThrows(T::class.java) { runBlocking { files.list(path) } }

    @Test
    fun `lists a share with a username and password`() {
        smb().use { files ->
            val entries = runBlocking { files.list("/") }.associateBy { it.name }

            // The image may keep files of its own in the share; ours must be there.
            assertTrue(entries.keys.containsAll(setOf("hello.txt", "Films")))
            assertEquals(5L, entries.getValue("hello.txt").size)
            assertEquals("/Films", entries.getValue("Films").path)
            assertTrue(entries.getValue("Films").isFolder)
            assertEquals(1, runBlocking { files.list("/Films") }.size)
        }
    }

    @Test
    fun `a wrong password is turned down`() {
        smb(password = "elm").use { files -> refusal<FileSystemException.AuthFailed>(files) }
    }

    @Test
    fun `a share that isn't there says so`() {
        smb(share = "Gone").use { files -> refusal<FileSystemException.ShareMissing>(files) }
    }

    @Test
    fun `a folder that isn't there says so`() {
        smb().use { files -> refusal<FileSystemException.NotFound>(files, "/Gone") }
    }

    companion object {
        private const val SMB_PORT = 445
        private lateinit var samba: GenericContainer<*>

        @BeforeClass
        @JvmStatic
        fun startSamba() {
            assumeTrue(
                "Docker isn't available, so the Samba tests are skipped",
                DockerClientFactory.instance().isDockerAvailable,
            )
            samba =
                GenericContainer(DockerImageName.parse("dperson/samba:latest"))
                    .withExposedPorts(SMB_PORT)
                    .withCommand("-u", "sam;oak", "-s", "Media;/media;yes;no;no;sam", "-p")
                    .withCopyToContainer(Transferable.of("hello"), "/media/hello.txt")
                    .withCopyToContainer(Transferable.of("film"), "/media/Films/a.mkv")
                    .waitingFor(Wait.forListeningPort())
            samba.start()
        }

        @AfterClass
        @JvmStatic
        fun stopSamba() {
            if (::samba.isInitialized) samba.stop()
        }
    }
}
