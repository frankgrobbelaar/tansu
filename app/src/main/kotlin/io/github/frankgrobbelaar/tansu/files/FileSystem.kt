package io.github.frankgrobbelaar.tansu.files

import java.io.InputStream
import java.io.OutputStream

/**
 * One place files live: the phone, an SMB share or an SFTP server. Screens and jobs only ever see
 * this interface. Paths are absolute within the file system and use `/` between names.
 *
 * Every call fails with a [FileSystemException]. The streams it hands out block, so they're used
 * off the main thread, and fail with [java.io.IOException] like any stream.
 */
interface FileSystem {
    /** What's in the folder at [path], in no particular order. */
    suspend fun list(path: String): List<Entry>

    /** The file or folder at [path], or null when there's nothing there. */
    suspend fun stat(path: String): Entry?

    /** Reads the file at [path], starting [offset] bytes in. The caller closes the stream. */
    suspend fun read(path: String, offset: Long = 0): InputStream

    /** Writes the file at [path] from its start, replacing what was there. */
    suspend fun write(path: String): OutputStream

    suspend fun createFolder(path: String)

    /** Moves [from] to [to] within this file system. Fails with Exists if [to] is taken. */
    suspend fun rename(from: String, to: String)

    /** Deletes the file, or the empty folder, at [path]. */
    suspend fun delete(path: String)
}

/** A file or folder as Browse shows it. */
data class Entry(
    val name: String,
    val path: String,
    val isFolder: Boolean,
    /** Bytes for a file; 0 for a folder. */
    val size: Long,
    /** When it last changed, in milliseconds since the epoch. */
    val modified: Long,
    /** How many things a folder holds, when that's cheap to know. */
    val childCount: Int? = null,
) {
    val isHidden: Boolean
        get() = isHiddenName(name)
}

/** Names starting with a dot are hidden, as on Linux and Android. */
internal fun isHiddenName(name: String) = name.startsWith(".")

/** A server's identity key, as its fingerprint is shown and pinned. */
data class HostKey(val type: String, val fingerprint: String)

/** What went wrong reaching a file system, in terms the UI can put into words. */
sealed class FileSystemException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    /** Tansu isn't allowed in: no All files access, or a folder the server keeps closed. */
    class AccessDenied(path: String) : FileSystemException("No access to $path")

    /** The folder or file isn't there (any more). */
    class NotFound(path: String) : FileSystemException("Nothing at $path")

    /** Something with that name is already there. */
    class Exists(path: String) : FileSystemException("Already at $path")

    /** The phone or server is full. */
    class NoSpace(path: String) : FileSystemException("No space for $path")

    /** The storage or server failed to read it, for a reason with no better name. */
    class Unreadable(path: String, cause: Throwable? = null) :
        FileSystemException("Can't read $path", cause)

    /** The server didn't answer: wrong name, switched off, or Tailscale is off. */
    class Unreachable(val host: String, cause: Throwable? = null) :
        FileSystemException("Can't reach $host", cause)

    /** The server turned the username, password or key down. */
    class AuthFailed(val host: String, cause: Throwable? = null) :
        FileSystemException("$host turned the login down", cause)

    /** The private key can't be read: not a key, or the passphrase is wrong. */
    class BadKey(cause: Throwable? = null) : FileSystemException("Unreadable private key", cause)

    /** The SMB share named for this drawer isn't on the server. */
    class ShareMissing(val share: String) : FileSystemException("No share called $share")

    /** The first connection to an SFTP server: its key needs checking before it's trusted. */
    class HostKeyUnknown(val key: HostKey) : FileSystemException("New key ${key.fingerprint}")

    /** An SFTP server presented a different key from the one pinned when it was added. */
    class HostKeyChanged(val trusted: String, val presented: HostKey) :
        FileSystemException("Key changed from $trusted to ${presented.fingerprint}")
}

/** [parent] and [name] joined with one `/`. */
fun join(parent: String, name: String) =
    if (parent.endsWith('/')) parent + name else "$parent/$name"

/** The folder holding [path]. */
fun parentOf(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

/** The last name in [path]. */
fun nameOf(path: String): String = path.substringAfterLast('/')

/** Whether [this] path is [root] or somewhere inside it. */
internal fun String.isWithin(root: String) =
    this == root || startsWith(if (root.endsWith('/')) root else "$root/")
