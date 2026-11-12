package io.github.frankgrobbelaar.tansu.files

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred

/**
 * A whole file system in memory, for jobs to copy, move and delete in. Tests can make a path fail,
 * make reads break part-way as a dropped connection does, or hold a read until they let it go. Like
 * the phone and SMB it can ignore case in names, and like SFTP it can hold links to folders.
 */
class MemoryFileSystem(
    private val root: String = "/",
    private val ignoreCase: Boolean = false,
) : FileSystem {
    private val files = mutableMapOf<String, ByteArray>()
    private val folders = mutableSetOf(root)
    private val links = mutableMapOf<String, String>()

    /** Fails any call that touches the path. */
    val failures = mutableMapOf<String, FileSystemException>()

    /** Holds the next read of the path until the test completes it. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Makes every read throw, as a dropped connection does, until set back to false. */
    var broken = false

    /** Every write's size, in the order they came. */
    val writes = mutableListOf<Int>()

    /** Makes every write say the disk is full, until set back to false. */
    var full = false

    /** Paths whose writing is refused, though everything else about them works. */
    val refusedWrites = mutableSetOf<String>()

    fun file(path: String, text: String) = apply {
        folderAt(parentOf(path))
        files[path] = text.toByteArray()
    }

    fun folderAt(path: String): MemoryFileSystem = apply {
        if (path != root && path !in folders) folderAt(parentOf(path))
        folders += path
    }

    /** Makes [path] a link to the folder [target], as SFTP shows one. */
    fun link(path: String, target: String) = apply { links[path] = target }

    fun text(path: String): String? = files[real(path)]?.decodeToString()

    fun exists(path: String) = path in links || real(path).let { it in files || it in folders }

    /** Every path, folders, files and links, under the root. */
    fun paths(): Set<String> = (files.keys + folders + links.keys - root).toSortedSet()

    override suspend fun list(path: String): List<Entry> {
        check(path)
        val at = real(path)
        if (at !in folders) throw FileSystemException.NotFound(path)
        val inside = childrenOf(at).map { entryOf(join(path, nameOf(it))) }
        val linked = links.keys.filter { parentOf(it) == path }.map(::entryOf)
        return inside + linked
    }

    override suspend fun stat(path: String): Entry? {
        check(path)
        return if (exists(path)) entryOf(path) else null
    }

    override suspend fun read(path: String, offset: Long): InputStream {
        check(path)
        gates.remove(path)?.await()
        val bytes = files[real(path)] ?: throw FileSystemException.NotFound(path)
        return object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (broken) throw IOException("Connection reset")
                    return super.read(b, off, len)
                }
            }
            .apply { skip(offset) }
    }

    override suspend fun write(path: String, replace: Boolean): OutputStream {
        check(path)
        val at = real(path)
        val there = taken(at)
        val refusal =
            when {
                path in refusedWrites -> FileSystemException.AccessDenied(path)
                parentOf(at) !in folders -> FileSystemException.NotFound(path)
                there != null && (!replace || there in folders) -> FileSystemException.Exists(path)
                else -> null
            }
        refusal?.let { throw it }
        // Written under the name that's there, as a case-blind file system does.
        val name = there ?: at
        files[name] = ByteArray(0)
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (full) throw StreamRefused(FileSystemException.NoSpace(path))
                writes += len
                files[name] = files.getValue(name) + b.copyOfRange(off, off + len)
            }
        }
    }

    override suspend fun createFolder(path: String) {
        check(path)
        val at = real(path)
        if (taken(at) != null || path in links) throw FileSystemException.Exists(path)
        if (parentOf(at) !in folders) throw FileSystemException.NotFound(path)
        folders += at
    }

    override suspend fun rename(from: String, to: String) {
        check(from)
        if (exists(to)) throw FileSystemException.Exists(to)
        if (!exists(from)) throw FileSystemException.NotFound(from)
        links.remove(from)?.let {
            links[to] = it
            return
        }
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
        // A link goes on its own, as SFTP's remove does.
        if (links.remove(path) != null) return
        val at = real(path)
        when {
            at in files -> files -= at
            at in folders && childrenOf(at).isEmpty() -> folders -= at
            at in folders -> throw FileSystemException.Unreadable(path)
            else -> throw FileSystemException.NotFound(path)
        }
    }

    /** Where [path] really is, through any link on the way. */
    private fun real(path: String): String {
        val link = links.keys.firstOrNull { path == it || path.isWithin(it) } ?: return path
        return links.getValue(link) + path.removePrefix(link)
    }

    /** What already has [path]'s name, telling case apart or not. */
    private fun taken(path: String): String? =
        (files.keys + folders).firstOrNull { it.equals(path, ignoreCase = ignoreCase) }

    private fun childrenOf(path: String) =
        (files.keys + folders).filter { it != path && parentOf(it) == path }

    private fun check(path: String) {
        failures[path]?.let { throw it }
    }

    private fun entryOf(path: String): Entry {
        val at = real(path)
        return Entry(
            name = nameOf(path),
            path = path,
            isFolder = at in folders,
            size = files[at]?.size?.toLong() ?: 0,
            modified = 0,
            isLink = path in links,
        )
    }
}
