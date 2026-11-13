package io.github.frankgrobbelaar.tansu.ui.viewer

import android.webkit.MimeTypeMap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import okio.buffer
import okio.source

/**
 * A picture in a drawer, as Coil loads it: straight from the phone or a server through the drawer's
 * file system, with no download first. Its size and date tell a changed file apart.
 */
data class DrawerImage(val drawerId: String, val path: String, val size: Long, val modified: Long) {
    constructor(
        drawerId: String,
        entry: Entry,
    ) : this(drawerId, entry.path, entry.size, entry.modified)
}

/**
 * Coil, able to load [DrawerImage]s and their [DrawerThumbnail]s from the [drawers], keeping the
 * thumbnails of servers' pictures in the cache folder.
 */
fun drawerImageLoader(context: PlatformContext, drawers: StateFlow<List<Drawer>>): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(DrawerImageFetcher.Factory(drawers))
            add(DrawerImageKeyer)
            add(DrawerThumbnailFetcher.Factory(drawers, File(context.cacheDir, THUMBNAIL_SCRATCH)))
            add(DrawerThumbnailKeyer)
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, THUMBNAILS))
                .maxSizeBytes(THUMBNAILS_MAX_BYTES)
                .build()
        }
        .build()

/** The cache folder thumbnails are kept in. */
private const val THUMBNAILS = "thumbnails"

/** Room for the thumbnails of some 6,000 photos, at about 40 KB each. */
private const val THUMBNAILS_MAX_BYTES = 256L * 1024 * 1024

/** The cache folder a server's picture comes down to while its thumbnail is made. */
internal const val THUMBNAIL_SCRATCH = "thumbnailing"

private class DrawerImageFetcher(
    private val image: DrawerImage,
    private val files: FileSystem,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val extension = image.path.substringAfterLast('.', "").lowercase()
        return SourceFetchResult(
            source = ImageSource(files.read(image.path).source().buffer(), options.fileSystem),
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension),
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val drawers: StateFlow<List<Drawer>>) : Fetcher.Factory<DrawerImage> {
        override fun create(
            data: DrawerImage,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? =
            drawers.value
                .find { it.id == data.drawerId }
                ?.let { DrawerImageFetcher(data, it.fileSystem, options) }
    }
}

/** Remembered by drawer, path, size and date, so a changed picture loads again. */
private object DrawerImageKeyer : Keyer<DrawerImage> {
    override fun key(data: DrawerImage, options: Options) =
        "${data.drawerId}:${data.path}:${data.size}:${data.modified}"
}
