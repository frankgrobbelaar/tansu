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
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.RenameFlags
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

    /** An SSH connection, signed in, with its SFTP channel open. */
    private class Session(val ssh: SSHClient, val sftp: SFTPClient)

    private val sessions =
        Reconnecting(
            open = { connect(secretOf(secrets, server)) },
            alive = { it.ssh.isConnected && it.ssh.isAuthenticated },
            end = ::hangUp,
        )

    override suspend fun list(path: String): List<Entry> =
        onServer(path) { sftp ->
            sftp.ls(path).map { info -> entryOf(info, sftp) }
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

    override suspend fun read(path: String, offset: Long): InputStream = openedFor { opened ->
        onServer(path) { sftp ->
            val file = sftp.open(path, EnumSet.of(OpenMode.READ))
            val stream = file.ReadAheadRemoteFileInputStream(READ_AHEAD, offset)
            opened(
                object : FilterInputStream(stream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            file.close()
                        }
                    }
                }
            )
        }
    }

    override suspend fun write(path: String, replace: Boolean): OutputStream = openedFor { opened ->
        onServer(path) { sftp ->
            val mode =
                if (replace) EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
                else EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
            val file =
                try {
                    sftp.open(path, mode)
                } catch (e: SFTPException) {
                    // SFTP 3 has no word for "already there": OpenSSH says only that it failed.
                    if (!replace && sftp.statExistence(path) != null) {
                        throw FileSystemException.Exists(path)
                    }
                    throw e
                }
            opened(SftpOutput(file.RemoteFileOutputStream(0, UNCONFIRMED_WRITES), file, path))
        }
    }

    override suspend fun createFolder(path: String) {
        onServer(path) { sftp ->
            try {
                sftp.mkdir(path)
            } catch (e: SFTPException) {
                // As with files, OpenSSH says only that it failed when the folder is there.
                if (sftp.statExistence(path) != null) throw FileSystemException.Exists(path)
                throw e
            }
        }
    }

    override suspend fun rename(from: String, to: String, replace: Boolean) {
        onServer(to) { sftp ->
            if (replace) {
                replaceWith(sftp, from, to)
            } else {
                // SFTP's rename quietly replaces on some servers: refuse if the name is taken.
                if (sftp.statExistence(to) != null) throw FileSystemException.Exists(to)
                sftp.rename(from, to)
            }
        }
    }

    /** [from] takes [to]'s place in one step where the server can, as OpenSSH's can. */
    private fun replaceWith(sftp: SFTPClient, from: String, to: String) {
        if (sftp.sftpEngine.supportsServerExtension("posix-rename", "openssh.com")) {
            sftp.rename(from, to, EnumSet.of(RenameFlags.OVERWRITE))
        } else {
            // A server without it: the old one goes first.
            if (sftp.statExistence(to) != null) sftp.rm(to)
            sftp.rename(from, to)
        }
    }

    override suspend fun delete(path: String) {
        onServer(path) { sftp ->
            // Not through a link: a link goes on its own, and what it points to stays.
            if (sftp.lstat(path).type == FileMode.Type.DIRECTORY) sftp.rmdir(path)
            else sftp.rm(path)
        }
    }

    override fun close() = sessions.close()

    /** Runs [block] on the server, off the main thread, connecting first if need be. */
    private suspend fun <T> onServer(path: String, block: (SFTPClient) -> T): T =
        withContext(io) { sessions.use { session -> mapErrors(path) { block(session.sftp) } } }

    private fun connect(secret: Secret): Session {
        val verifier = PinnedKey(server.hostKey)
        val client =
            SSHClient(DefaultConfig()).apply {
                connectTimeout = TIMEOUT_MILLIS
                timeout = TIMEOUT_MILLIS
                // The key exchange and each request wait this long too, not sshj's 30 seconds.
                transport.timeoutMs = TIMEOUT_MILLIS
                connection.timeoutMs = TIMEOUT_MILLIS
                addHostKeyVerifier(verifier)
            }
        var connected = false
        try {
            mapErrors("") {
                try {
                    client.connect(server.host, server.port)
                } catch (e: IOException) {
                    verifier.refusal()?.let { throw it }
                    throw e
                }
                signIn(client, secret)
            }
            val sftp =
                mapErrors("") {
                    client.newSFTPClient().apply { sftpEngine.timeoutMs = TIMEOUT_MILLIS }
                }
            connected = true
            return Session(client, sftp)
        } finally {
            if (!connected) quietly { client.disconnect() }
        }
    }

    private fun hangUp(session: Session) {
        quietly { session.sftp.close() }
        quietly { session.ssh.disconnect() }
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
        } catch (e: UserAuthException) {
            throw FileSystemException.AuthFailed(server.host, e)
        } catch (e: SFTPException) {
            throw when (e.statusCode) {
                Response.StatusCode.PERMISSION_DENIED -> FileSystemException.AccessDenied(path)
                Response.StatusCode.NO_SUCH_FILE,
                Response.StatusCode.NO_SUCH_PATH -> FileSystemException.NotFound(path)
                Response.StatusCode.FILE_ALREADY_EXISTS -> FileSystemException.Exists(path)
                Response.StatusCode.NO_SPACE_ON_FILESYSTEM,
                Response.StatusCode.QUOTA_EXCEEDED -> FileSystemException.NoSpace(path)
                // sshj's word for a request that timed out or a channel that closed.
                Response.StatusCode.UNKNOWN,
                Response.StatusCode.NO_CONNECTION,
                Response.StatusCode.CONNECITON_LOST ->
                    FileSystemException.Unreachable(server.host, e)
                else -> FileSystemException.Unreadable(path, e)
            }
        } catch (e: IOException) {
            throw FileSystemException.Unreachable(server.host, e)
        } catch (e: SSHRuntimeException) {
            // sshj's own failures that aren't the server answering no: a broken connection.
            throw FileSystemException.Unreachable(server.host, e)
        }

    /** An entry for [info]. A link shows as what it points to, so that's looked up. */
    private fun entryOf(info: RemoteResourceInfo, sftp: SFTPClient): Entry {
        val isLink = info.attributes.type == FileMode.Type.SYMLINK
        val attributes = (if (isLink) sftp.statExistence(info.path) else null) ?: info.attributes
        val isFolder = attributes.type == FileMode.Type.DIRECTORY
        return Entry(
            name = info.name,
            path = info.path,
            isFolder = isFolder,
            size = if (isFolder) 0 else attributes.size,
            modified = attributes.mtime * MILLIS_PER_SECOND,
            isLink = isLink,
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val MILLIS_PER_SECOND = 1000L
        /** Requests kept in flight, so a transfer isn't one round trip per chunk. */
        const val READ_AHEAD = 16
        const val UNCONFIRMED_WRITES = 16
    }
}

/**
 * A file on the server being written. sshj sends each write as one SFTP message and OpenSSH refuses
 * any over 256 KiB, so writes go in small pieces. A full disk or a refusal is said as one, so it
 * isn't waited out like a dropped connection.
 */
private class SftpOutput(
    private val stream: OutputStream,
    private val file: Closeable,
    private val path: String,
) : FilterOutputStream(stream) {
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        var at = offset
        while (at < offset + length) {
            val piece = minOf(MAX_WRITE, offset + length - at)
            refusals { stream.write(buffer, at, piece) }
            at += piece
        }
    }

    override fun flush() = refusals { stream.flush() }

    override fun close() {
        try {
            refusals { super.close() }
        } finally {
            file.close()
        }
    }

    private inline fun refusals(block: () -> Unit) {
        try {
            block()
        } catch (e: SFTPException) {
            throw when (e.statusCode) {
                Response.StatusCode.NO_SPACE_ON_FILESYSTEM,
                Response.StatusCode.QUOTA_EXCEEDED ->
                    StreamRefused(FileSystemException.NoSpace(path))
                Response.StatusCode.PERMISSION_DENIED ->
                    StreamRefused(FileSystemException.AccessDenied(path))
                // OpenSSH's word for a full disk, a quota, a file too big or a read-only one.
                Response.StatusCode.FAILURE ->
                    StreamRefused(FileSystemException.Unreadable(path, e))
                else -> e
            }
        }
    }

    private companion object {
        /** What sshj's own uploads send at a time. */
        const val MAX_WRITE = 32 * 1024
    }
}

/** Ends what's left of a connection; one that's already gone is ended enough. */
private inline fun quietly(block: () -> Unit) {
    try {
        block()
    } catch (_: IOException) {
        // Gone already.
    } catch (_: SSHRuntimeException) {
        // Gone already.
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
