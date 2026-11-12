package io.github.frankgrobbelaar.tansu.files

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The phone's own storage, read with `java.nio` under All files access. */
class LocalFileSystem(private val io: CoroutineDispatcher = Dispatchers.IO) : FileSystem {

    override suspend fun list(path: String): List<Entry> =
        withContext(io) {
            try {
                Files.newDirectoryStream(Paths.get(path)).use { children ->
                    // A big folder takes a while: stop as soon as nobody wants the listing.
                    children.mapNotNull { child ->
                        ensureActive()
                        entryOf(child)
                    }
                }
            } catch (_: AccessDeniedException) {
                throw FileSystemException.AccessDenied(path)
            } catch (_: NoSuchFileException) {
                throw FileSystemException.NotFound(path)
            } catch (_: NotDirectoryException) {
                throw FileSystemException.NotFound(path)
            } catch (e: IOException) {
                throw FileSystemException.Unreadable(path, e)
            } catch (e: DirectoryIteratorException) {
                throw FileSystemException.Unreadable(path, e)
            }
        }

    /** An entry for [file], or nothing when it vanished or can't be read since it was listed. */
    private fun entryOf(file: Path): Entry? =
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            Entry(
                name = file.name,
                path = file.invariantSeparatorsPathString,
                isFolder = attributes.isDirectory,
                size = if (attributes.isDirectory) 0 else attributes.size(),
                modified = attributes.lastModifiedTime().toMillis(),
                childCount = if (attributes.isDirectory) childCount(file) else null,
            )
        } catch (_: IOException) {
            null
        }

    /** What a folder holds as Browse shows it, so hidden files aren't counted. */
    private fun childCount(folder: Path): Int? =
        try {
            Files.newDirectoryStream(folder).use { children ->
                children.count { !isHiddenName(it.name) }
            }
        } catch (_: IOException) {
            null
        } catch (_: DirectoryIteratorException) {
            null
        }
}
