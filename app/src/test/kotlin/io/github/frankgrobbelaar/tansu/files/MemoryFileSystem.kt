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

    /** Fails any call that touches the path, as where it comes from or goes to. */
    val failures = mutableMapOf<String, FileSystemException>()

    /** Holds the next read of the path until the test completes it. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Makes every read throw, as a dropped connection does, until set back to false. */
    var broken = false

    /** Every write's size, in the order they came. */
    val writes = mutableListOf<Int>()

    /** Where each carried-on write began, and where each read began. */
    val writeOffsets = mutableListOf<Long>()
    val readOffsets = mutableListOf<Long>()

    /** Makes every write say the disk is full, until set back to false. */
    var full = false

    /** Folders that can be listed and read, but not written in. */
    val readOnly = mutableSetOf<String>()

    /** Fails deleting the path, though everything else about it works. */
    val deleteFailures = mutableMapOf<String, FileSystemException>()

    /** Paths whose next rename or delete happens, but whose answer is lost on the way back. */
    val lostAnswers = mutableSetOf<String>()

    /** Files still being written: once read, each grows by its text, as a recording does. */
    val growing = mutableMapOf<String, String>()

    /** Holds the next file opened for writing in the folder, once it's made, until let go. */
    val writeGates = mutableMapOf<String, CompletableDeferred<Unit>>()

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

    fun text(path: String): String? = held(real(path))?.let { files[it] }?.decodeToString()

    fun exists(path: String) = path in links || held(real(path)) != null

    /** Every path, folders, files and links, under the root. */
    fun paths(): Set<String> = (files.keys + folders + links.keys - root).toSortedSet()

    override suspend fun list(path: String): List<Entry> {
        check(path)
        val at =
            held(real(path))?.takeIf { it in folders } ?: throw FileSystemException.NotFound(path)
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
        readOffsets += offset
        val bytes = held(real(path))?.let { files[it] } ?: throw FileSystemException.NotFound(path)
        growing.remove(path)?.let { more -> files[held(real(path))!!] = bytes + more.toByteArray() }
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
        val there = held(at)
        val refusal =
            when {
                parentOf(at) in readOnly -> FileSystemException.AccessDenied(path)
                held(parentOf(at)) !in folders -> FileSystemException.NotFound(path)
                there != null && (!replace || there in folders) -> FileSystemException.Exists(path)
                else -> null
            }
        refusal?.let { throw it }
        fits(path)
        // Written under the name that's there, as a case-blind file system does.
        val name = there ?: at
        files[name] = ByteArray(0)
        writeGates.remove(parentOf(path))?.await()
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (full) throw StreamRefused(FileSystemException.NoSpace(path))
                writes += len
                files[name] = files.getValue(name) + b.copyOfRange(off, off + len)
            }
        }
    }

    override suspend fun writeAt(path: String, offset: Long): OutputStream {
        check(path)
        val name =
            held(real(path))?.takeIf { it in files } ?: throw FileSystemException.NotFound(path)
        // What was written from the offset on is written again.
        files[name] = files.getValue(name).copyOf(offset.toInt())
        writeOffsets += offset
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
        val refusal =
            when {
                held(at) != null || path in links -> FileSystemException.Exists(path)
                held(parentOf(at)) !in folders -> FileSystemException.NotFound(path)
                parentOf(at) in readOnly -> FileSystemException.AccessDenied(path)
                else -> null
            }
        refusal?.let { throw it }
        folders += at
    }

    override suspend fun rename(from: String, to: String, replace: Boolean) {
        check(from)
        check(to)
        fits(to)
        moveName(from, to, replace)
        answer(to)
    }

    private fun moveName(from: String, to: String, replace: Boolean) {
        links.remove(from)?.let {
            links[to] = it
            return
        }
        val source = held(real(from)) ?: throw FileSystemException.NotFound(from)
        val there = held(real(to))
        if (there != null && there != source) {
            if (!replace || there in folders || source in folders) {
                throw FileSystemException.Exists(to)
            }
            files -= there
        }
        val moved = { path: String -> real(to) + path.removePrefix(source) }
        files.keys
            .filter { it == source || it.isWithin(source) }
            .forEach { files[moved(it)] = files.remove(it)!! }
        folders
            .filter { it == source || it.isWithin(source) }
            .forEach {
                folders -= it
                folders += moved(it)
            }
    }

    override suspend fun delete(path: String) {
        check(path)
        check(path, deleteFailures)
        // A link goes on its own, as SFTP's remove does.
        if (links.remove(path) != null) return
        val at = held(real(path)) ?: throw FileSystemException.NotFound(path)
        when {
            at in files -> files -= at
            childrenOf(at).isEmpty() -> folders -= at
            else -> throw FileSystemException.Unreadable(path)
        }
        answer(path)
    }

    /** What was done is done; the answer to it may not arrive. */
    private fun answer(path: String) {
        if (lostAnswers.remove(path)) throw FileSystemException.Unreachable("lost the answer")
    }

    /** Names are at most 255 long, as on the phone. */
    private fun fits(path: String) {
        if (nameOf(path).length > MAX_NAME) throw FileSystemException.Unreadable(path)
    }

    /** Where [path] really is, through any link on the way. */
    private fun real(path: String): String {
        val link = links.keys.firstOrNull { path == it || path.isWithin(it) } ?: return path
        return links.getValue(link) + path.removePrefix(link)
    }

    /** The file or folder that has [path]'s name, telling case apart or not. */
    private fun held(path: String): String? =
        if (path in files || path in folders) path
        else (files.keys + folders).firstOrNull { it.equals(path, ignoreCase = ignoreCase) }

    private fun childrenOf(path: String) =
        (files.keys + folders).filter { it != path && parentOf(it) == path }

    private fun check(path: String, failing: Map<String, FileSystemException> = failures) {
        failing[path]?.let { throw it }
    }

    private fun entryOf(path: String): Entry {
        val at = held(real(path))
        return Entry(
            name = nameOf(path),
            path = path,
            isFolder = at in folders,
            size = at?.let { files[it] }?.size?.toLong() ?: 0,
            modified = 0,
            isLink = path in links,
        )
    }

    private companion object {
        const val MAX_NAME = 255
    }
}
