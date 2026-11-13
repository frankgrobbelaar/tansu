package io.github.frankgrobbelaar.tansu.files

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session as SmbSession
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import io.github.frankgrobbelaar.tansu.servers.Secrets
import io.github.frankgrobbelaar.tansu.servers.Server
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file system that can say which shares its server has, as SMB's can. */
interface ListsShares {
    /** The disk shares on the server that this login can see, by name. */
    suspend fun shares(): List<String>
}

/** An SMB share (SMB 2 or 3) through smbj, with one connection kept open and reused. */
class SmbFileSystem(
    private val server: Server,
    private val secrets: Secrets,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystem, ListsShares, Closeable {

    // No socket timeout: smbj treats one as a dead connection, so an idle minute would end it.
    // Each request still has its own, and connecting has its own too, longer than smbj's five
    // seconds, which a server reached through Tailscale can take to answer while it wakes up.
    private val client =
        SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withSocketFactory(ProxySocketFactory(CONNECT_TIMEOUT_MILLIS))
                .withTimeout(ANSWER_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
                .withReadBufferSize(READ_CHUNK)
                .build()
        )

    /** A connection signed in to the server, with the share open. */
    private class Session(val connection: Connection, val share: DiskShare)

    private val sessions =
        Reconnecting(
            open = ::connect,
            alive = { it.connection.isConnected && it.share.isConnected },
            end = { session -> quietly { session.connection.close(true) } },
        )

    override suspend fun list(path: String): List<Entry> =
        onShare(path) { share ->
            share
                .list(smbPath(path))
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { entryOf(path, it) }
        }

    override suspend fun stat(path: String): Entry? =
        onShare(path) { share ->
            try {
                val info = share.getFileInformation(smbPath(path))
                Entry(
                    name = nameOf(path),
                    path = path,
                    isFolder = info.standardInformation.isDirectory,
                    size =
                        if (info.standardInformation.isDirectory) 0
                        else info.standardInformation.endOfFile,
                    modified = info.basicInformation.lastWriteTime.toEpochMillis(),
                )
            } catch (e: SMBApiException) {
                if (e.status.name in NOT_FOUND) null else throw e
            }
        }

    override suspend fun read(path: String, offset: Long): InputStream = openedFor { opened ->
        onShare(path) { share ->
            val file =
                share.openFile(
                    smbPath(path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
            opened(SmbInput(file, offset))
        }
    }

    override suspend fun write(path: String, replace: Boolean): OutputStream = openedFor { opened ->
        onShare(path) { share ->
            val file =
                share.openFile(
                    smbPath(path),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    // A new file fails if the name is taken, in any case, rather than overwriting.
                    if (replace) SMB2CreateDisposition.FILE_OVERWRITE_IF
                    else SMB2CreateDisposition.FILE_CREATE,
                    null,
                )
            opened(SmbOutput(file, path))
        }
    }

    override suspend fun writeAt(path: String, offset: Long): OutputStream = openedFor { opened ->
        onShare(path) { share ->
            val file =
                share.openFile(
                    smbPath(path),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
            opened(SmbOutput(file, path, start = offset))
        }
    }

    override suspend fun createFolder(path: String) =
        madeOnce(path) { onShare(path, retry = false) { share -> share.mkdir(smbPath(path)) } }

    override suspend fun rename(from: String, to: String, replace: Boolean) {
        // Tried once: done twice, its second go would find nothing to rename.
        onShare(to, retry = false) { share ->
            share
                .open(
                    smbPath(from),
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                .use { it.rename(smbPath(to), replace) }
        }
    }

    override suspend fun delete(path: String) {
        onShare(path) { share ->
            val target = smbPath(path)
            if (share.getFileInformation(target).standardInformation.isDirectory) {
                share.rmdir(target, false)
            } else {
                share.rm(target)
            }
        }
    }

    /**
     * Asks the server which shares it has, for when [Server.share] isn't one of them. On a
     * connection of its own, it signs in, asks the srvsvc pipe on IPC$, and hangs up.
     */
    override suspend fun shares(): List<String> =
        withContext(io) {
            val session = signIn()
            try {
                askForShares(session)
            } finally {
                quietly { session.connection.close(true) }
            }
        }

    override fun close() = sessions.close()

    /** Runs [block] on the share, off the main thread, connecting first if need be. */
    private suspend fun <T> onShare(
        path: String,
        retry: Boolean = true,
        block: (DiskShare) -> T,
    ): T =
        withContext(io) {
            sessions.use(retry) { session -> mapErrors(path) { block(session.share) } }
        }

    private suspend fun connect(): Session {
        val session = signIn()
        var connected = false
        try {
            val share = mapErrors("") { openShare(session) }
            connected = true
            return Session(session.connection, share)
        } finally {
            if (!connected) quietly { session.connection.close(true) }
        }
    }

    /** A new connection to the server, signed in. It's ended again if signing in fails. */
    private suspend fun signIn(): SmbSession {
        val secret = secretOf(secrets, server)
        val connection = mapErrors("") { client.connect(server.host, server.port) }
        var signedIn = false
        try {
            val login =
                if (server.signIn == Server.SignIn.Guest) AuthenticationContext.guest()
                else AuthenticationContext(server.username, secret.password.toCharArray(), null)
            val session =
                mapErrors("") {
                    try {
                        connection.authenticate(login)
                    } catch (e: SMBApiException) {
                        // Any refusal while signing in is about the login, whatever status it comes
                        // with. A connection lost meanwhile is still the network's.
                        throw FileSystemException.AuthFailed(server.host, e)
                    }
                }
            signedIn = true
            return session
        } finally {
            if (!signedIn) quietly { connection.close(true) }
        }
    }

    private fun openShare(session: SmbSession): DiskShare =
        try {
            session.connectShare(server.share) as? DiskShare
        } catch (_: IllegalArgumentException) {
            // smbj won't take a share name with a backslash in it.
            null
        } ?: throw FileSystemException.ShareMissing(server.share)

    /** Asks the srvsvc pipe on IPC$ for the server's shares: a bind, then NetrShareEnum. */
    private fun askForShares(session: SmbSession): List<String> =
        try {
            mapErrors(IPC) {
                val ipc =
                    session.connectShare(IPC) as? PipeShare
                        ?: throw ShareEnumFailed("$IPC isn't a share of pipes")
                // Hanging up closes the pipe and the share with it, without waiting on the server.
                val pipe =
                    ipc.open(
                        SRVSVC_PIPE,
                        SMB2ImpersonationLevel.Impersonation,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                        null,
                        EnumSet.of(
                            SMB2ShareAccess.FILE_SHARE_READ,
                            SMB2ShareAccess.FILE_SHARE_WRITE,
                        ),
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                Srvsvc.checkBound(pipe.call(Srvsvc.bind()))
                Srvsvc.shares(pipe.call(Srvsvc.shareEnum()))
            }
        } catch (e: ShareEnumFailed) {
            throw if (e.denied) FileSystemException.AccessDenied(IPC)
            else FileSystemException.Unreadable(IPC, e)
        }

    /** Turns smbj's and the network's failures into the ones the UI explains. */
    private inline fun <T> mapErrors(path: String, block: () -> T): T =
        try {
            block()
        } catch (e: SMBApiException) {
            throw when (e.status.name) {
                in LOGIN_REFUSED -> FileSystemException.AuthFailed(server.host, e)
                "STATUS_BAD_NETWORK_NAME" -> FileSystemException.ShareMissing(server.share)
                "STATUS_ACCESS_DENIED" -> FileSystemException.AccessDenied(path)
                "STATUS_OBJECT_NAME_COLLISION" -> FileSystemException.Exists(path)
                "STATUS_DISK_FULL" -> FileSystemException.NoSpace(path)
                in NOT_FOUND -> FileSystemException.NotFound(path)
                // The server ended the session or the share: connect again.
                in GONE -> FileSystemException.Unreachable(server.host, e)
                else -> FileSystemException.Unreadable(path, e)
            }
        } catch (e: SMBRuntimeException) {
            // smbj's own failures that aren't the server answering no: a dropped connection.
            throw FileSystemException.Unreachable(server.host, e)
        } catch (e: IOException) {
            throw FileSystemException.Unreachable(server.host, e)
        }

    private fun entryOf(parent: String, info: FileIdBothDirectoryInformation): Entry {
        val isFolder = EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        return Entry(
            name = info.fileName,
            path = join(parent, info.fileName),
            isFolder = isFolder,
            size = if (isFolder) 0 else info.endOfFile,
            modified = info.lastWriteTime.toEpochMillis(),
            isLink = isLink(info),
        )
    }

    /**
     * A Windows link or junction: removing it removes only the link. Other reparse points, such as
     * deduplicated files or cloud placeholders, are ordinary files.
     */
    private fun isLink(info: FileIdBothDirectoryInformation): Boolean =
        EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT) &&
            // With a reparse point, the field holds its tag.
            info.eaSize in setOf(REPARSE_SYMLINK, REPARSE_MOUNT_POINT)

    private companion object {
        /** The share every SMB server has for its named pipes, and the pipe that lists shares. */
        const val IPC = "IPC$"
        const val SRVSVC_PIPE = "srvsvc"
        const val REPARSE_SYMLINK = 0xA000000CL
        const val REPARSE_MOUNT_POINT = 0xA0000003L
        val LOGIN_REFUSED =
            setOf(
                "STATUS_LOGON_FAILURE",
                "STATUS_WRONG_PASSWORD",
                "STATUS_ACCOUNT_DISABLED",
                "STATUS_ACCOUNT_RESTRICTION",
                "STATUS_ACCOUNT_LOCKED_OUT",
                "STATUS_ACCOUNT_EXPIRED",
                "STATUS_PASSWORD_EXPIRED",
                "STATUS_PASSWORD_MUST_CHANGE",
            )
        val GONE =
            setOf(
                "STATUS_USER_SESSION_DELETED",
                "STATUS_NETWORK_SESSION_EXPIRED",
                "STATUS_NETWORK_NAME_DELETED",
            )
        val NOT_FOUND =
            setOf(
                "STATUS_OBJECT_NAME_NOT_FOUND",
                "STATUS_OBJECT_PATH_NOT_FOUND",
                "STATUS_NOT_A_DIRECTORY",
            )

        /** smbj wants share-relative paths with backslashes. */
        fun smbPath(path: String) = path.trim('/').replace('/', '\\')
    }
}

/**
 * What a read asks the server for at a time: big enough that a server across Tailscale isn't a
 * round trip per few kilobytes, small enough that a slow link answers each in time.
 */
private const val READ_CHUNK = 256 * 1024

/**
 * A file on the share read from [position] on, a chunk at a time, and the next chunk only once the
 * last is used up, so a stream closed early leaves nothing on its way. smbj's failures become I/O
 * ones: a read the server turns down fails, rather than ending the file early as smbj's own stream
 * does.
 */
private class SmbInput(private val file: File, private var position: Long) : InputStream() {
    private val chunk = ByteArray(READ_CHUNK)
    private var next = 0
    private var held = 0
    private var ended = false

    override fun read(): Int = if (fill()) chunk[next++].toInt() and BYTE else -1

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!fill()) return -1
        val count = minOf(length, held - next)
        chunk.copyInto(buffer, offset, next, next + count)
        next += count
        return count
    }

    override fun available(): Int = held - next

    override fun close() = smbIo { file.close() }

    /** Whether there's more to read, asking the server for the next chunk if need be. */
    private fun fill(): Boolean {
        if (next < held) return true
        if (ended) return false
        val read = smbIo { file.read(chunk, position, 0, chunk.size) }
        if (read <= 0) {
            ended = true
            return false
        }
        position += read
        next = 0
        held = read
        return true
    }
}

private const val BYTE = 0xFF

/**
 * A file on the share written from its start, as a stream. smbj's failures become I/O ones; a full
 * share or a refusal is said as one, so it isn't waited out like a dropped connection.
 */
private class SmbOutput(private val file: File, private val path: String, start: Long = 0) :
    OutputStream() {
    private var position = start

    override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = refusals {
        position += file.write(buffer, position, offset, length)
    }

    override fun close() = refusals { file.close() }

    private inline fun refusals(block: () -> Unit) {
        try {
            block()
        } catch (e: SMBApiException) {
            throw when (e.status.name) {
                "STATUS_DISK_FULL" -> StreamRefused(FileSystemException.NoSpace(path))
                "STATUS_ACCESS_DENIED" -> StreamRefused(FileSystemException.AccessDenied(path))
                else -> IOException(e)
            }
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
    }
}

/**
 * Sends [pdu] down the pipe and reads the answer, to its last fragment: a long list of shares can
 * come in several, and the first read may not hold them all.
 */
private fun NamedPipe.call(pdu: ByteArray): ByteArray {
    var answer = transact(pdu)
    val chunk = ByteArray(Srvsvc.MAX_FRAGMENT)
    while (!Srvsvc.isWhole(answer)) {
        val read = read(chunk)
        if (read <= 0) throw ShareEnumFailed("The answer stopped part-way")
        answer += chunk.copyOf(read)
    }
    return answer
}

/** Ends a connection without waiting on the server; one that's already gone is ended enough. */
private inline fun quietly(block: () -> Unit) {
    try {
        block()
    } catch (_: IOException) {
        // Gone already.
    } catch (_: SMBRuntimeException) {
        // The server answered the goodbye with an error: still gone.
    }
}

/** Runs smbj [block], turning its runtime failures into the I/O failures streams promise. */
private inline fun <T> smbIo(block: () -> T): T =
    try {
        block()
    } catch (e: SMBRuntimeException) {
        throw IOException(e)
    }
