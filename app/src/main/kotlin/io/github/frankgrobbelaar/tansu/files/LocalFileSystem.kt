package io.github.frankgrobbelaar.tansu.files

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone's own storage, read with `java.nio` under All files access. */
class LocalFileSystem(private val io: CoroutineDispatcher = Dispatchers.IO) : FileSystem {

    override suspend fun list(path: String): List<Entry> =
        withContext(io) {
            val folder = Paths.get(path)
            try {
                Files.newDirectoryStream(folder).use { children -> children.mapNotNull(::entryOf) }
            } catch (_: AccessDeniedException) {
                throw FileSystemException.AccessDenied(path)
            } catch (_: NoSuchFileException) {
                throw FileSystemException.NotFound(path)
            } catch (_: NotDirectoryException) {
                throw FileSystemException.NotFound(path)
            }
        }

    /** An entry for [file], or nothing when it vanished or can't be read since it was listed. */
    private fun entryOf(file: Path): Entry? =
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            Entry(
                name = file.name,
                path = file.toString(),
                isFolder = attributes.isDirectory,
                size = if (attributes.isDirectory) 0 else attributes.size(),
                modified = attributes.lastModifiedTime().toMillis(),
                childCount = if (attributes.isDirectory) childCount(file) else null,
            )
        } catch (_: IOException) {
            null
        }

    private fun childCount(folder: Path): Int? =
        try {
            Files.newDirectoryStream(folder).use { children -> children.count() }
        } catch (_: IOException) {
            null
        }
}
