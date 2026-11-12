package io.github.frankgrobbelaar.tansu.files

import kotlinx.coroutines.CompletableDeferred

/**
 * A file system made of a map from folder paths to what they hold, or to what goes wrong. A gate
 * holds a folder's listing back until the test opens it.
 */
class FakeFileSystem(private val folders: Map<String, List<Entry>> = emptyMap()) :
    FileSystem by MemoryFileSystem() {
    val failures = mutableMapOf<String, FileSystemException>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var listings = 0
        private set

    override suspend fun list(path: String): List<Entry> {
        listings++
        gates[path]?.await()
        failures[path]?.let { throw it }
        return folders[path] ?: throw FileSystemException.NotFound(path)
    }

    companion object {
        fun folder(parent: String, name: String, childCount: Int = 0) =
            Entry(name, "$parent/$name", isFolder = true, size = 0, modified = 0, childCount)

        fun file(parent: String, name: String, size: Long = 1) =
            Entry(name, "$parent/$name", isFolder = false, size = size, modified = 0)
    }
}
