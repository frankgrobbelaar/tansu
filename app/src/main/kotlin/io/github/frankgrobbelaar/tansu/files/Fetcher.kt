package io.github.frankgrobbelaar.tansu.files

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Copies files from a server into tansu's cache, so another app can open or share them: other apps
 * can't read a server, only a file on the phone. Each fetch replaces the last one's files.
 */
class Fetcher(private val cache: File, private val io: CoroutineDispatcher = Dispatchers.IO) {

    /** Fetches [entries] from [files] one after another, telling [progress] the bytes so far. */
    suspend fun fetch(
        files: FileSystem,
        entries: List<Entry>,
        progress: (Long) -> Unit,
    ): List<File> =
        withContext(io) {
            val folder =
                File(cache, FOLDER).apply {
                    deleteRecursively()
                    mkdirs()
                }
            var done = 0L
            entries.map { entry ->
                val target = File(folder, entry.name)
                files.read(entry.path).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(CHUNK)
                        try {
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                progress(done)
                            }
                        } catch (e: IOException) {
                            throw FileSystemException.Unreadable(entry.path, e)
                        }
                    }
                }
                target
            }
        }

    companion object {
        /** The cache folder fetched files go in; the FileProvider shares it as "fetched". */
        const val FOLDER = "fetched"
        private const val CHUNK = 256 * 1024
    }
}
