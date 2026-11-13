package io.github.frankgrobbelaar.tansu.files

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Files another app shared with tansu, read through Android's content resolver. A path here is the
 * file's content URI. They can only be read: tansu copies them into a drawer.
 */
class SharedFiles(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystem {

    /** Each shared file as an entry: its name and size, as the app sharing it says. */
    suspend fun entries(uris: List<Uri>): List<Entry> = withContext(io) { uris.map { entryOf(it) } }

    // The stream is the caller's to close, as FileSystem.read says.
    @SuppressLint("Recycle")
    override suspend fun read(path: String, offset: Long): InputStream = openedFor { opened ->
        withContext(io) {
            asked(path) {
                val input =
                    resolver.openInputStream(path.toUri()) ?: throw FileNotFoundException(path)
                opened(input).from(offset)
            }
        }
    }

    override suspend fun stat(path: String): Entry = withContext(io) { entryOf(path.toUri()) }

    override suspend fun list(path: String): List<Entry> = throw readOnly(path)

    override suspend fun write(path: String, replace: Boolean): OutputStream = throw readOnly(path)

    override suspend fun createFolder(path: String) = throw readOnly(path)

    override suspend fun rename(from: String, to: String, replace: Boolean) = throw readOnly(from)

    override suspend fun delete(path: String) = throw readOnly(path)

    private fun readOnly(path: String) = FileSystemException.AccessDenied(path)

    /** Runs [block] against the app that shared [path], its failures put in tansu's terms. */
    private fun <T> asked(path: String, block: () -> T): T =
        try {
            block()
        } catch (_: FileNotFoundException) {
            throw FileSystemException.NotFound(path)
        } catch (_: SecurityException) {
            // The app that shared it took its permission back.
            throw FileSystemException.AccessDenied(path)
        } catch (e: IOException) {
            throw FileSystemException.Unreadable(path, e)
        } catch (e: IllegalArgumentException) {
            // Another app's provider answers in its own ways.
            throw FileSystemException.Unreadable(path, e)
        } catch (e: IllegalStateException) {
            throw FileSystemException.Unreadable(path, e)
        }

    private fun entryOf(uri: Uri): Entry {
        var name: String? = null
        var size = 0L
        try {
            resolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
        } catch (_: SecurityException) {
            // Its name and size are the app's to give; the copy will say if it can't be read.
        } catch (_: IllegalArgumentException) {
            // Another app's provider answers in its own ways: a URI or a column it won't take.
        } catch (_: IllegalStateException) {
            // Or a cursor it can't fill.
        }
        // A name is a file name, never a path.
        val fileName = (name ?: uri.lastPathSegment ?: "shared").substringAfterLast('/')
        return Entry(fileName, uri.toString(), isFolder = false, size = size, modified = 0)
    }
}
