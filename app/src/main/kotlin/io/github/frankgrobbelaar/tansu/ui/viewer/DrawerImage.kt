package io.github.frankgrobbelaar.tansu.ui.viewer

import android.webkit.MimeTypeMap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
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

/** Coil, able to load [DrawerImage]s from the [drawers]. */
fun drawerImageLoader(context: PlatformContext, drawers: StateFlow<List<Drawer>>): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(DrawerImageFetcher.Factory(drawers))
            add(DrawerImageKeyer)
        }
        .build()

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
