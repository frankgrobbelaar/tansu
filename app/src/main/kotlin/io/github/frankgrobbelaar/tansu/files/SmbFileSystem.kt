package io.github.frankgrobbelaar.tansu.files

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import io.github.frankgrobbelaar.tansu.servers.Secrets
import io.github.frankgrobbelaar.tansu.servers.Server
import java.io.Closeable
import java.io.IOException
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
        withContext(io) {
            val share = share()
            mapErrors(path) {
                share
                    .list(smbPath(path))
                    .filter { it.fileName != "." && it.fileName != ".." }
                    .map { entryOf(path, it) }
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
