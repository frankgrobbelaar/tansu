package io.github.frankgrobbelaar.tansu.files

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred

/**
 * A whole file system in memory, for jobs to copy, move and delete in. Tests can make a path fail,
 * make reads break part-way as a dropped connection does, or hold a read until they let it go.
 */
class MemoryFileSystem(private val root: String = "/") : FileSystem {
    private val files = mutableMapOf<String, ByteArray>()
    private val folders = mutableSetOf(root)

    /** Fails any call that touches the path. */
    val failures = mutableMapOf<String, FileSystemException>()

    /** Holds the next read of the path until the test completes it. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Makes every read throw, as a dropped connection does, until set back to false. */
    var broken = false

    fun file(path: String, text: String) = apply {
        folderAt(parentOf(path))
        files[path] = text.toByteArray()
    }

    fun folderAt(path: String): MemoryFileSystem = apply {
        if (path != root && path !in folders) folderAt(parentOf(path))
        folders += path
    }

    fun text(path: String): String? = files[path]?.decodeToString()

    fun exists(path: String) = path in files || path in folders

    /** Every path, folders and files, under the root. */
    fun paths(): Set<String> = (files.keys + folders - root).toSortedSet()

    override suspend fun list(path: String): List<Entry> {
        check(path)
        if (path !in folders) throw FileSystemException.NotFound(path)
        return childrenOf(path).map(::entryOf)
    }

    private fun childrenOf(path: String) =
        (files.keys + folders).filter { it != path && parentOf(it) == path }

    override suspend fun stat(path: String): Entry? {
        check(path)
        return if (exists(path)) entryOf(path) else null
    }

    override suspend fun read(path: String, offset: Long): InputStream {
        check(path)
        gates.remove(path)?.await()
        val bytes = files[path] ?: throw FileSystemException.NotFound(path)
        return object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (broken) throw IOException("Connection reset")
                    return super.read(b, off, len)
                }
            }
            .apply { skip(offset) }
    }

    override suspend fun write(path: String): OutputStream {
        check(path)
        if (parentOf(path) !in folders) throw FileSystemException.NotFound(path)
        files[path] = ByteArray(0)
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                files[path] = files.getValue(path) + b.copyOfRange(off, off + len)
            }
        }
    }

    override suspend fun createFolder(path: String) {
        check(path)
        if (exists(path)) throw FileSystemException.Exists(path)
        if (parentOf(path) !in folders) throw FileSystemException.NotFound(path)
        folders += path
    }

    override suspend fun rename(from: String, to: String) {
        check(from)
        if (exists(to)) throw FileSystemException.Exists(to)
        if (!exists(from)) throw FileSystemException.NotFound(from)
        val moved = { path: String -> to + path.removePrefix(from) }
        files.keys
            .filter { it == from || it.isWithin(from) }
            .forEach { files[moved(it)] = files.remove(it)!! }
        folders
            .filter { it == from || it.isWithin(from) }
            .forEach {
                folders -= it
                folders += moved(it)
            }
    }

    override suspend fun delete(path: String) {
        check(path)
        when {
            path in files -> files -= path
            path in folders && childrenOf(path).isEmpty() -> folders -= path
            path in folders -> throw FileSystemException.Unreadable(path)
            else -> throw FileSystemException.NotFound(path)
        }
    }

    private fun check(path: String) {
        failures[path]?.let { throw it }
    }

    private fun entryOf(path: String) =
        Entry(
            name = nameOf(path),
            path = path,
            isFolder = path in folders,
            size = files[path]?.size?.toLong() ?: 0,
            modified = 0,
        )
}
