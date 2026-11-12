package io.github.frankgrobbelaar.tansu.files

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An SMB share (SMB 2 or 3) through smbj, with one connection kept open and reused. */
class SmbFileSystem(
    private val server: Server,
    private val secrets: Secrets,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystem, Closeable {

    private val client =
        SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .withSoTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        )
    private val lock = Mutex()
    private var connection: Connection? = null
    private var share: DiskShare? = null

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

    override suspend fun read(path: String, offset: Long): InputStream =
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
            SmbInput(file, offset)
        }

    override suspend fun write(path: String): OutputStream =
        onShare(path) { share ->
            val file =
                share.openFile(
                    smbPath(path),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null,
                )
            SmbOutput(file)
        }

    override suspend fun createFolder(path: String) {
        onShare(path) { share -> share.mkdir(smbPath(path)) }
    }

    override suspend fun rename(from: String, to: String) {
        onShare(to) { share ->
            share
                .open(
                    smbPath(from),
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                .use { it.rename(smbPath(to)) }
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

    override fun close() {
        try {
            connection?.close()
        } catch (_: IOException) {
            // Closing a connection that's already gone is fine.
        }
        connection = null
        share = null
    }

    /** Runs [block] on the share, off the main thread, connecting first if need be. */
    private suspend fun <T> onShare(path: String, block: (DiskShare) -> T): T =
        withContext(io) {
            val share = share()
            mapErrors(path) { block(share) }
        }

    private suspend fun share(): DiskShare = lock.withLock {
        share?.takeIf { it.isConnected }
            ?: run {
                close()
                connect().also { share = it }
            }
    }

    private suspend fun connect(): DiskShare {
        val secret = secrets.read(server.id)
        return mapErrors("") {
            val connection = client.connect(server.host, server.port).also { connection = it }
            val login =
                if (server.signIn == Server.SignIn.Guest) AuthenticationContext.guest()
                else AuthenticationContext(server.username, secret.password.toCharArray(), null)
            connection.authenticate(login).connectShare(server.share) as? DiskShare
                ?: throw FileSystemException.ShareMissing(server.share)
        }
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
                else -> FileSystemException.Unreadable(path, e)
            }
        } catch (e: SMBRuntimeException) {
            // smbj's own failures that aren't the server answering no: a dropped connection.
            close()
            throw FileSystemException.Unreachable(server.host, e)
        } catch (e: IOException) {
            close()
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
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        val LOGIN_REFUSED =
            setOf(
                "STATUS_LOGON_FAILURE",
                "STATUS_ACCOUNT_DISABLED",
                "STATUS_ACCOUNT_RESTRICTION",
                "STATUS_PASSWORD_EXPIRED",
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

/** A file on the share read from [position] on, as a stream; smbj's failures become I/O ones. */
private class SmbInput(private val file: File, private var position: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = smbIo {
        val read = file.read(buffer, position, offset, length)
        if (read > 0) position += read
        if (read <= 0) -1 else read
    }

    override fun close() = smbIo { file.close() }
}

/** A file on the share written from its start, as a stream; smbj's failures become I/O ones. */
private class SmbOutput(private val file: File) : OutputStream() {
    private var position = 0L

    override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = smbIo {
        position += file.write(buffer, position, offset, length)
    }

    override fun close() = smbIo { file.close() }
}

private const val BYTE = 0xFF

/** Runs smbj [block], turning its runtime failures into the I/O failures streams promise. */
private inline fun <T> smbIo(block: () -> T): T =
    try {
        block()
    } catch (e: SMBRuntimeException) {
        throw IOException(e)
    }
