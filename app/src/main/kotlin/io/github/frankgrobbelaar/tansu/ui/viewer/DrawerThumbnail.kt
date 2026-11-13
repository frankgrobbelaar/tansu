package io.github.frankgrobbelaar.tansu.ui.viewer

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * A picture's thumbnail, as a grid tile shows it: small and quick, and for a server's picture, kept
 * on the phone for next time. The picture's size and date tell a changed one apart.
 */
data class DrawerThumbnail(val picture: DrawerImage) {
    constructor(drawerId: String, entry: Entry) : this(DrawerImage(drawerId, entry))

    /** What it's remembered by, in memory and on disk. */
    internal val key: String
        get() = with(picture) { "thumbnail:$drawerId:$path:$size:$modified" }
}

/**
 * Finds a thumbnail kept from before, or makes one: from the camera's own in a JPEG's EXIF data,
 * reading only the start of the file, or else from the whole picture, made small.
 */
internal class DrawerThumbnailFetcher(
    private val thumbnail: DrawerThumbnail,
    private val drawer: Drawer,
    /** Where thumbnails are kept; none for the phone's pictures. */
    private val cache: DiskCache?,
    /** Taken while one is made, so only a few are made at once in each drawer. */
    private val turns: Semaphore,
    /** Where a server's picture comes down to the phone while it's made small. */
    private val scratch: Lazy<File>,
    /** Servers' pictures, by key, that turned out not to be pictures Android can read. */
    private val unreadable: MutableSet<String>,
) : Fetcher {
    private val path = thumbnail.picture.path

    override suspend fun fetch(): FetchResult =
        withContext(Dispatchers.IO) { cache?.let(::kept) ?: made() }

    /** The thumbnail kept from an earlier visit, if there is one. */
    private fun kept(cache: DiskCache): FetchResult? =
        cache.openSnapshot(thumbnail.key)?.let { snapshot ->
            SourceFetchResult(
                source =
                    ImageSource(
                        file = snapshot.data,
                        fileSystem = cache.fileSystem,
                        diskCacheKey = thumbnail.key,
                        closeable = snapshot,
                    ),
                // A JPEG, or a WebP: it's told by what's in it.
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }

    /** A thumbnail made now, and kept for next time. It's shown as made, not read back. */
    private suspend fun made(): FetchResult {
        // One that couldn't be read isn't brought down whole again while tansu runs.
        if (thumbnail.key in unreadable) throw IOException("$path can't be read as a picture")
        val bitmap = turns.withPermit { camerasOwn() ?: wholeMadeSmall() }
        cache?.keep(thumbnail.key, bitmap)
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource =
                if (drawer.kind == Drawer.Kind.Phone) DataSource.DISK else DataSource.NETWORK,
        )
    }

    /** The camera's own thumbnail, which only a JPEG has. */
    private suspend fun camerasOwn(): Bitmap? {
        if (path.substringAfterLast('.', "").lowercase() !in JPEG) return null
        val stream = drawer.fileSystem.read(path)
        return try {
            exifThumbnail(stream)
        } finally {
            closeAfterReading(stream)
        }
    }

    /** The whole picture, made small: in place on the phone, and from a copy for a server's. */
    private suspend fun wholeMadeSmall(): Bitmap =
        if (drawer.kind == Drawer.Kind.Phone) {
            smallPicture(File(path))
        } else {
            val copy = File.createTempFile("picture", null, scratch.value)
            try {
                drawer.fileSystem.read(path).use { it.saveTo(copy) }
                copyMadeSmall(copy)
            } finally {
                copy.delete()
            }
        }

    /** A server's picture, come down to [copy], made small; or remembered as one Android can't. */
    private fun copyMadeSmall(copy: File): Bitmap =
        try {
            smallPicture(copy)
        } catch (e: IOException) {
            unreadable += thumbnail.key
            throw e
        }

    /**
     * Finds each thumbnail's drawer, and where it's kept; a server's picture comes down to [folder]
     * to be made small. Each drawer makes four at a time, as reads from an SMB server all share its
     * one connection: a screenful of tiles at once would slow browsing and every tile alike, while
     * in turns the top ones show first. A tile scrolled away before its turn reads nothing.
     */
    class Factory(private val drawers: StateFlow<List<Drawer>>, folder: File) :
        Fetcher.Factory<DrawerThumbnail> {
        private val turns = ConcurrentHashMap<String, Semaphore>()
        private val unreadable = ConcurrentHashMap.newKeySet<String>()

        /** Emptied, before it's first used, of any copies a process that ended mid-way left. */
        private val scratch = lazy {
            folder.apply {
                deleteRecursively()
                mkdirs()
            }
        }

        override fun create(
            data: DrawerThumbnail,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val drawer = drawers.value.find { it.id == data.picture.drawerId } ?: return null
            return DrawerThumbnailFetcher(
                thumbnail = data,
                drawer = drawer,
                // The phone's pictures make thumbnails about as quickly as the cache gives them
                // back, so the cache's room is kept for servers', which are slow to make.
                cache = imageLoader.diskCache.takeIf { drawer.kind != Drawer.Kind.Phone },
                turns = turns.computeIfAbsent(drawer.id) { Semaphore(AT_ONCE) },
                scratch = scratch,
                unreadable = unreadable,
            )
        }
    }

    private companion object {
        val JPEG = setOf("jpg", "jpeg")
        const val AT_ONCE = 4
    }
}

/** Remembered by drawer, path, size and date, so a changed picture's thumbnail is made again. */
internal object DrawerThumbnailKeyer : Keyer<DrawerThumbnail> {
    override fun key(data: DrawerThumbnail, options: Options) = data.key
}

/** Closes [stream] once what's wanted is read from it: a server gone since can't take that back. */
private fun closeAfterReading(stream: InputStream) {
    try {
        stream.close()
    } catch (_: IOException) {
        // Closing only says goodbye; what was read stands.
    }
}

/** Saves the rest of this stream to [file], stopping as soon as nobody wants it. */
private suspend fun InputStream.saveTo(file: File) {
    val buffer = ByteArray(PIECE)
    file.outputStream().use { output ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }
}

/** What's read at a time: as much as an SMB server sends in one go. */
private const val PIECE = 256 * 1024
