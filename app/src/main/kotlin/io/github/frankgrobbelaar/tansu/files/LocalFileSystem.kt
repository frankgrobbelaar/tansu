package io.github.frankgrobbelaar.tansu.files

import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
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
            mapErrors(path) {
                Files.newDirectoryStream(Paths.get(path)).use { children ->
                    // A big folder takes a while: stop as soon as nobody wants the listing.
                    children.mapNotNull { child ->
                        ensureActive()
                        entryOf(child)
                    }
                }
            }
        }

    override suspend fun stat(path: String): Entry? =
        withContext(io) { mapErrors(path) { entryOf(Paths.get(path), counted = false) } }

    override suspend fun read(path: String, offset: Long): InputStream =
        withContext(io) {
            mapErrors(path) {
                Files.newInputStream(Paths.get(path)).apply { if (offset > 0) skipNBytes(offset) }
            }
        }

    override suspend fun write(path: String, replace: Boolean): OutputStream =
        withContext(io) {
            mapErrors(path) {
                val options =
                    if (replace)
                        arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    else arrayOf(StandardOpenOption.CREATE_NEW)
                LocalOutput(Files.newOutputStream(Paths.get(path), *options), path)
            }
        }

    override suspend fun createFolder(path: String) {
        withContext(io) { mapErrors(path) { Files.createDirectory(Paths.get(path)) } }
    }

    override suspend fun rename(from: String, to: String) {
        withContext(io) { mapErrors(to) { Files.move(Paths.get(from), Paths.get(to)) } }
    }

    override suspend fun delete(path: String) {
        withContext(io) { mapErrors(path) { Files.delete(Paths.get(path)) } }
    }

    /** An entry for [file], or nothing when it vanished or can't be read since it was listed. */
    private fun entryOf(file: Path, counted: Boolean = true): Entry? =
        try {
            val own =
                Files.readAttributes(
                    file,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            // A link shows as what it points to.
            val attributes =
                if (own.isSymbolicLink) Files.readAttributes(file, BasicFileAttributes::class.java)
                else own
            Entry(
                name = file.name,
                path = file.invariantSeparatorsPathString,
                isFolder = attributes.isDirectory,
                size = if (attributes.isDirectory) 0 else attributes.size(),
                modified = attributes.lastModifiedTime().toMillis(),
                childCount =
                    if (attributes.isDirectory && counted && !own.isSymbolicLink) childCount(file)
                    else null,
                isLink = own.isSymbolicLink,
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

    /** Turns `java.nio`'s failures into the ones the UI explains. */
    private inline fun <T> mapErrors(path: String, block: () -> T): T =
        try {
            block()
        } catch (_: AccessDeniedException) {
            throw FileSystemException.AccessDenied(path)
        } catch (_: NoSuchFileException) {
            throw FileSystemException.NotFound(path)
        } catch (_: NotDirectoryException) {
            throw FileSystemException.NotFound(path)
        } catch (_: FileAlreadyExistsException) {
            throw FileSystemException.Exists(path)
        } catch (e: DirectoryNotEmptyException) {
            throw FileSystemException.Unreadable(path, e)
        } catch (e: IOException) {
            throw if (e.message?.contains(NO_SPACE) == true) FileSystemException.NoSpace(path)
            else FileSystemException.Unreadable(path, e)
        } catch (e: DirectoryIteratorException) {
            throw FileSystemException.Unreadable(path, e)
        }

    /** A file being written, with a full phone said as one rather than as a broken stream. */
    private class LocalOutput(out: OutputStream, private val path: String) :
        FilterOutputStream(out) {
        override fun write(b: Int) = refusals { out.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = refusals { out.write(b, off, len) }

        override fun flush() = refusals { out.flush() }

        override fun close() = refusals { super.close() }

        private inline fun refusals(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                throw if (e.message?.contains(NO_SPACE) == true) {
                    StreamRefused(FileSystemException.NoSpace(path))
                } else {
                    e
                }
            }
        }
    }

    private companion object {
        /** How Linux words ENOSPC. */
        const val NO_SPACE = "No space left"
    }
}
