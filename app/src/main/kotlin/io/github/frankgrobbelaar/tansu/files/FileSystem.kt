package io.github.frankgrobbelaar.tansu.files

/**
 * One place files live: the phone, an SMB share or an SFTP server. Screens and jobs only ever see
 * this interface. Paths are absolute within the file system and use `/` between names.
 */
interface FileSystem {
    /** What's in the folder at [path], in no particular order. */
    suspend fun list(path: String): List<Entry>
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
        get() = name.startsWith(".")
}

/** What went wrong reaching a file system, in terms the UI can put into words. */
sealed class FileSystemException(message: String) : Exception(message) {
    /** Tansu isn't allowed in: no All files access, or a folder the server keeps closed. */
    class AccessDenied(path: String) : FileSystemException("No access to $path")

    /** The folder or file isn't there (any more). */
    class NotFound(path: String) : FileSystemException("Nothing at $path")
}
