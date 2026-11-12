package io.github.frankgrobbelaar.tansu.files

import io.github.frankgrobbelaar.tansu.servers.Secret
import io.github.frankgrobbelaar.tansu.servers.Secrets
import io.github.frankgrobbelaar.tansu.servers.Server
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils

/**
 * An SFTP server through sshj, with one SSH connection kept open and reused. The server's key must
 * match the one pinned when it was added; a server never seen before is refused until its key is
 * trusted.
 */
class SftpFileSystem(
    private val server: Server,
    private val secrets: Secrets,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystem, Closeable {

    private val lock = Mutex()
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    override suspend fun list(path: String): List<Entry> =
        onServer(path) { sftp ->
            sftp.ls(path).map(::entryOf)
        }

    override suspend fun stat(path: String): Entry? =
        onServer(path) { sftp ->
            sftp.statExistence(path)?.let { attributes ->
                val isFolder = attributes.type == FileMode.Type.DIRECTORY
                Entry(
                    name = nameOf(path),
                    path = path,
                    isFolder = isFolder,
                    size = if (isFolder) 0 else attributes.size,
                    modified = attributes.mtime * MILLIS_PER_SECOND,
                )
            }
        }

    override suspend fun read(path: String, offset: Long): InputStream =
        onServer(path) { sftp ->
            val file = sftp.open(path, EnumSet.of(OpenMode.READ))
            val stream = file.ReadAheadRemoteFileInputStream(READ_AHEAD, offset)
            object : FilterInputStream(stream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        file.close()
                    }
                }
            }
        }

    override suspend fun write(path: String): OutputStream =
        onServer(path) { sftp ->
            val file = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
            val stream = file.RemoteFileOutputStream(0, UNCONFIRMED_WRITES)
            object : FilterOutputStream(stream) {
                // FilterOutputStream writes byte by byte; hand whole buffers on.
                override fun write(buffer: ByteArray, offset: Int, length: Int) =
                    stream.write(buffer, offset, length)

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        file.close()
                    }
                }
            }
        }

    override suspend fun createFolder(path: String) {
        onServer(path) { sftp -> sftp.mkdir(path) }
    }

    override suspend fun rename(from: String, to: String) {
        onServer(to) { sftp ->
            // SFTP's rename quietly replaces on some servers: refuse if the name is taken.
            if (sftp.statExistence(to) != null) throw FileSystemException.Exists(to)
            sftp.rename(from, to)
        }
    }

    override suspend fun delete(path: String) {
        onServer(path) { sftp ->
            if (sftp.stat(path).type == FileMode.Type.DIRECTORY) sftp.rmdir(path) else sftp.rm(path)
        }
    }

    override fun close() {
        try {
            sftp?.close()
            ssh?.disconnect()
        } catch (_: IOException) {
            // Closing a connection that's already gone is fine.
        }
        sftp = null
        ssh = null
    }

    /** Runs [block] on the server, off the main thread, connecting first if need be. */
    private suspend fun <T> onServer(path: String, block: (SFTPClient) -> T): T =
        withContext(io) {
            val sftp = sftp()
            mapErrors(path) { block(sftp) }
        }

    private suspend fun sftp(): SFTPClient = lock.withLock {
        sftp?.takeIf { ssh?.isConnected == true }
            ?: run {
                close()
                connect(secrets.read(server.id)).also { sftp = it }
            }
    }

    private fun connect(secret: Secret): SFTPClient {
        val verifier = PinnedKey(server.hostKey)
        val client =
            SSHClient(DefaultConfig()).apply {
                connectTimeout = TIMEOUT_MILLIS
                timeout = TIMEOUT_MILLIS
                addHostKeyVerifier(verifier)
            }
        ssh = client
        mapErrors("") {
            try {
                client.connect(server.host, server.port)
            } catch (e: IOException) {
                verifier.refusal()?.let { throw it }
                throw e
            }
            signIn(client, secret)
        }
        return mapErrors("") { client.newSFTPClient() }
    }

    private fun signIn(client: SSHClient, secret: Secret) {
        when (server.signIn) {
            Server.SignIn.Key -> client.authPublickey(server.username, keyOf(client, secret))
            else -> client.authPassword(server.username, secret.password)
        }
    }

    /** The private key, read now so a bad file or passphrase is told apart from a refusal. */
    private fun keyOf(client: SSHClient, secret: Secret): KeyProvider {
        val passphrase =
            secret.passphrase
                .takeIf { it.isNotEmpty() }
                ?.let {
                    PasswordUtils.createOneOff(it.toCharArray())
                }
        return try {
            client.loadKeys(secret.privateKey, null, passphrase).also { it.private }
        } catch (e: IOException) {
            throw FileSystemException.BadKey(e)
        } catch (e: SSHRuntimeException) {
            throw FileSystemException.BadKey(e)
        }
    }

    /** Turns sshj's and the network's failures into the ones the UI explains. */
    private inline fun <T> mapErrors(path: String, block: () -> T): T =
        try {
            block()
        } catch (e: FileSystemException) {
            close()
            throw e
        } catch (e: UserAuthException) {
            close()
            throw FileSystemException.AuthFailed(server.host, e)
        } catch (e: SFTPException) {
            throw when (e.statusCode) {
                Response.StatusCode.PERMISSION_DENIED -> FileSystemException.AccessDenied(path)
                Response.StatusCode.NO_SUCH_FILE,
                Response.StatusCode.NO_SUCH_PATH -> FileSystemException.NotFound(path)
                Response.StatusCode.FILE_ALREADY_EXISTS -> FileSystemException.Exists(path)
                Response.StatusCode.NO_SPACE_ON_FILESYSTEM,
                Response.StatusCode.QUOTA_EXCEEDED -> FileSystemException.NoSpace(path)
                else -> FileSystemException.Unreadable(path, e)
            }
        } catch (e: IOException) {
            close()
            throw FileSystemException.Unreachable(server.host, e)
        } catch (e: SSHRuntimeException) {
            // sshj's own failures that aren't the server answering no: a broken connection.
            close()
            throw FileSystemException.Unreachable(server.host, e)
        }

    private fun entryOf(info: RemoteResourceInfo) =
        Entry(
            name = info.name,
            path = info.path,
            isFolder = info.isDirectory,
            size = if (info.isDirectory) 0 else info.attributes.size,
            modified = info.attributes.mtime * MILLIS_PER_SECOND,
        )

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val MILLIS_PER_SECOND = 1000L
        /** Requests kept in flight, so a transfer isn't one round trip per chunk. */
        const val READ_AHEAD = 16
        const val UNCONFIRMED_WRITES = 16
    }
}

/** Accepts only the pinned key, remembering what was presented so a refusal can say why. */
private class PinnedKey(private val pinned: String) : HostKeyVerifier {
    private var presented: HostKey? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val seen = hostKeyOf(key).also { presented = it }
        return pinned.isNotEmpty() && seen.fingerprint == pinned
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    /** Why the server's key was refused, when it was. */
    fun refusal(): FileSystemException? {
        val seen = presented ?: return null
        return when {
            pinned.isEmpty() -> FileSystemException.HostKeyUnknown(seen)
            seen.fingerprint != pinned -> FileSystemException.HostKeyChanged(pinned, seen)
            else -> null
        }
    }
}

/** A key's type and its SHA-256 fingerprint, as `ssh-keygen -lf` shows it. */
fun hostKeyOf(key: PublicKey): HostKey {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return HostKey(
        // "ssh-ed25519" reads as ED25519, "ecdsa-sha2-nistp256" as ECDSA.
        type =
            KeyType.fromKey(key).toString().removePrefix("ssh-").substringBefore('-').uppercase(),
        fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest),
    )
}
