package io.github.frankgrobbelaar.tansu.ui.viewer

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import coil3.BitmapImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerThumbnailTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nasFiles = Pictures()
    private val phoneFiles = Pictures()
    private val nas = Drawer("nas", "NAS", Drawer.Kind.Smb, root = "/", fileSystem = nasFiles)
    private val phone =
        Drawer("phone", "Phone", Drawer.Kind.Phone, root = "/", fileSystem = phoneFiles)
    private val loader = drawerImageLoader(context, MutableStateFlow(listOf(phone, nas)))
    private val scratch = File(context.cacheDir, THUMBNAIL_SCRATCH)

    /** A phone's photo with a thumbnail of its own, as a Galaxy's has, padded out to 2 MB. */
    private val photo = picture(1024, 768).jpeg().withThumbnail(speckled()).padded(2_000_000)

    @After fun shutDown() = loader.shutdown()

    /** The thumbnail of [entry], fetched as Coil fetches it, without decoding what's kept. */
    private suspend fun fetch(entry: Entry, drawer: Drawer = nas): FetchResult {
        val thumbnail = DrawerThumbnail(drawer.id, entry)
        val (fetcher, _) =
            checkNotNull(loader.components.newFetcher(thumbnail, Options(context), loader))
        return checkNotNull(fetcher.fetch())
    }

    @Test
    fun `a server's thumbnail is kept, and found again without reading the picture`() =
        runBlocking {
            val entry = nasFiles.put("/Photos/a.jpg", photo)

            val made = fetch(entry) as ImageFetchResult
            val kept = fetch(entry) as SourceFetchResult

            assertEquals(DataSource.NETWORK, made.dataSource)
            assertEquals(DataSource.DISK, kept.dataSource)
            assertEquals(1, nasFiles.opened.get())
            assertTrue("Read ${nasFiles.bytesRead}", nasFiles.bytesRead < 64 * 1024)
            val jpeg = kept.source.use { it.fileSystem.read(it.file()) { readByteArray() } }
            val back = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertEquals(512 to 384, back.width to back.height)
        }

    @Test
    fun `a picture that changed has its thumbnail made again`() = runBlocking {
        val entry = nasFiles.put("/Photos/a.jpg", photo)

        fetch(entry)
        val touched = fetch(entry.copy(modified = entry.modified + 1000))
        val grown = fetch(entry.copy(size = entry.size + 1))

        assertEquals(DataSource.NETWORK, touched.dataSource())
        assertEquals(DataSource.NETWORK, grown.dataSource())
        assertEquals(3, nasFiles.opened.get())
    }

    @Test
    fun `the phone's thumbnails aren't kept, being as quick to make again`() = runBlocking {
        val entry = phoneFiles.put("/DCIM/Camera/a.jpg", photo)

        val first = fetch(entry, phone) as ImageFetchResult
        val second = fetch(entry, phone) as ImageFetchResult

        assertEquals(DataSource.DISK, first.dataSource)
        assertEquals(DataSource.DISK, second.dataSource)
        assertEquals(2, phoneFiles.opened.get())
        assertNull(loader.diskCache?.openSnapshot(DrawerThumbnail(phone.id, entry).key))
    }

    @Test
    fun `a photo without a thumbnail of its own is read whole and made small`() = runBlocking {
        assumeImageDecoderOpensFiles()
        val plain = picture(1600, 1200).jpeg().padded(1_000_000)
        val entry = nasFiles.put("/Photos/b.jpg", plain)

        val made = fetch(entry) as ImageFetchResult

        val bitmap = (made.image as BitmapImage).bitmap
        assertEquals(512 to THUMBNAIL_SIDE, bitmap.width to bitmap.height)
        // The start, for a thumbnail, then the whole of it.
        assertEquals(2, nasFiles.opened.get())
        assertTrue(nasFiles.bytesRead > plain.size)
        assertEquals(emptyList<String>(), scratch.list().orEmpty().toList())
        assertEquals(DataSource.DISK, fetch(entry).dataSource())
    }

    @Test
    fun `a picture that isn't a JPEG is read once, whole`() = runBlocking {
        assumeImageDecoderOpensFiles()
        val entry = nasFiles.put("/Photos/c.png", picture(800, 600).png())

        val made = fetch(entry) as ImageFetchResult

        val bitmap = (made.image as BitmapImage).bitmap
        assertEquals(512 to THUMBNAIL_SIDE, bitmap.width to bitmap.height)
        assertEquals(1, nasFiles.opened.get())
    }

    @Test
    fun `a thumbnail no longer wanted stops reading, and leaves nothing behind`() = runBlocking {
        val entry = nasFiles.put("/Photos/d.png", ByteArray(1_000_000))
        val hold = CountDownLatch(1)
        nasFiles.hold = hold

        val fetching = launch(Dispatchers.IO) { fetch(entry) }
        assertTrue(nasFiles.holding.await(5, TimeUnit.SECONDS))
        fetching.cancel()
        hold.countDown()
        fetching.join()

        assertEquals(1, nasFiles.closed.get())
        assertTrue(nasFiles.bytesRead < 1_000_000)
        assertEquals(emptyList<String>(), scratch.list().orEmpty().toList())
        assertNull(loader.diskCache?.openSnapshot(DrawerThumbnail(nas.id, entry).key))
    }

    @Test
    fun `each drawer makes four thumbnails at a time`() = runBlocking {
        val entries = (1..6).map { nasFiles.put("/Photos/$it.jpg", photo) }
        val gate = CompletableDeferred<Unit>()
        nasFiles.gate = gate

        val fetches = entries.map { async(Dispatchers.IO) { fetch(it) } }
        withTimeout(5_000) { while (nasFiles.reading.get() < 4) delay(10) }
        // Time enough for the other two to start, if they could.
        delay(200)
        val atOnce = nasFiles.reading.get()
        gate.complete(Unit)
        val results = fetches.awaitAll()

        assertEquals(4, atOnce)
        assertEquals(4, nasFiles.most.get())
        assertTrue(results.all { it is ImageFetchResult })
    }

    private fun FetchResult.dataSource() =
        when (this) {
            is ImageFetchResult -> dataSource
            is SourceFetchResult -> dataSource.also { source.close() }
        }
}
