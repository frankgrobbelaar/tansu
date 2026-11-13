package io.github.frankgrobbelaar.tansu.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.MemoryFileSystem
import io.github.frankgrobbelaar.tansu.files.nameOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assume.assumeFalse

/** A [width] × [height] picture, each pixel the colour [paint] gives its place. */
fun picture(width: Int, height: Int, paint: (x: Int, y: Int) -> Int = { _, _ -> Color.GRAY }) =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(width * height) { paint(it % width, it / width) }
        setPixels(pixels, 0, width, 0, 0, width, height)
    }

/** This as a JPEG, as Android writes one: without EXIF data. */
fun Bitmap.jpeg(quality: Int = 90): ByteArray = encoded(Bitmap.CompressFormat.JPEG, quality)

fun Bitmap.png(): ByteArray = encoded(Bitmap.CompressFormat.PNG, 100)

private fun Bitmap.encoded(format: Bitmap.CompressFormat, quality: Int) =
    ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

/** A camera's thumbnail as big as phones' are: 512 × 384, about 50 KB of speckles. */
fun speckled(): ByteArray {
    val random = Random(1)
    return picture(512, 384) { _, _ -> random.nextInt() or Color.BLACK }.jpeg(quality = 30)
}

/**
 * This JPEG with a camera's [thumbnail] in EXIF data straight after its start, as cameras write it:
 * the photo's IFD saying its [orientation], then the thumbnail's IFD, pointing at the thumbnail.
 */
fun ByteArray.withThumbnail(
    thumbnail: ByteArray,
    orientation: Int = ExifInterface.ORIENTATION_NORMAL,
): ByteArray {
    // A big-endian TIFF header, the photo's IFD at 8 (18 bytes), the thumbnail's at 26 (42).
    val at = 68
    val tiff =
        ByteBuffer.allocate(at + thumbnail.size)
            .put("MM".toByteArray())
            .putShort(42)
            .putInt(8)
            .putShort(1)
            .entry(0x0112, SHORT, orientation)
            .putInt(26)
            .putShort(3)
            .entry(0x0103, SHORT, 6) // Compression: JPEG
            .entry(0x0201, LONG, at) // JPEGInterchangeFormat
            .entry(0x0202, LONG, thumbnail.size) // JPEGInterchangeFormatLength
            .putInt(0)
            .put(thumbnail)
            .array()
    val segment = "Exif\u0000\u0000".toByteArray() + tiff
    val app1 =
        ByteBuffer.allocate(4).putShort(0xFFE1.toShort()).putShort((segment.size + 2).toShort())
    return copyOfRange(0, 2) + app1.array() + segment + copyOfRange(2, size)
}

private fun ByteBuffer.entry(tag: Int, type: Short, value: Int): ByteBuffer {
    putShort(tag.toShort()).putShort(type).putInt(1)
    return if (type == SHORT) putShort(value.toShort()).putShort(0) else putInt(value)
}

private const val SHORT: Short = 3
private const val LONG: Short = 4

/** This JPEG, its frame header saying it's [width] × [height], whatever its picture data holds. */
fun ByteArray.claimingSize(width: Int, height: Int): ByteArray {
    val claiming = copyOf()
    // Past each segment to the frame header: its marker, length and precision, then the size.
    var at = 2
    while (this[at + 1] != FRAME) at +=
        2 + ByteBuffer.wrap(this, at + 2, 2).short.toUShort().toInt()
    ByteBuffer.wrap(claiming, at + 5, 4).putShort(height.toShort()).putShort(width.toShort())
    return claiming
}

private const val FRAME = 0xC0.toByte()

/**
 * This picture with [bytes] more after its end, as a big photo would be: decoders stop at its end.
 */
fun ByteArray.padded(bytes: Int) = this + ByteArray(bytes)

/** [bytes] as a stream that says how much of it was read, and the most asked for at once. */
class Counting(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    val consumed: Int
        get() = pos

    var largestRead = 0
        private set

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        largestRead = maxOf(largestRead, len)
        return super.read(b, off, len)
    }
}

/**
 * Pictures in memory by path, counting the files opened and the bytes read from them. A [gate]
 * holds every file back as it opens; a [hold] holds every stream's second read, once [holding] says
 * so.
 */
class Pictures : FileSystem by MemoryFileSystem() {
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val streams = CopyOnWriteArrayList<Stream>()
    val opened = AtomicInteger()
    val closed = AtomicInteger()

    /** Files open just now, and the most there have been at once. */
    val reading = AtomicInteger()
    val most = AtomicInteger()

    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** What closing a stream throws, as when the server has gone by then. */
    @Volatile var closing: IOException? = null
    @Volatile var hold: CountDownLatch? = null
    val holding = CountDownLatch(1)

    val bytesRead: Int
        get() = streams.sumOf { it.consumed }

    fun put(path: String, bytes: ByteArray): Entry {
        files[path] = bytes
        return Entry(nameOf(path), path, isFolder = false, size = bytes.size.toLong(), modified = 1)
    }

    override suspend fun read(path: String, offset: Long): InputStream {
        require(offset == 0L) { "Thumbnails are read from the start" }
        val bytes = files[path] ?: throw FileSystemException.NotFound(path)
        opened.incrementAndGet()
        most.accumulateAndGet(reading.incrementAndGet()) { a, b -> maxOf(a, b) }
        gate?.await()
        return Stream(bytes).also { streams += it }
    }

    private inner class Stream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val consumed: Int
            get() = pos

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            hold
                ?.takeIf { pos > 0 }
                ?.let {
                    holding.countDown()
                    it.await()
                }
            return super.read(b, off, len)
        }

        override fun close() {
            reading.decrementAndGet()
            closed.incrementAndGet()
            closing?.let { throw it }
        }
    }
}

/** Robolectric's ImageDecoder opens files on Linux and macOS, as in CI, but not on Windows. */
fun assumeImageDecoderOpensFiles() =
    assumeFalse(
        "Robolectric's ImageDecoder can't open files on Windows, so this runs in CI",
        System.getProperty("os.name").orEmpty().startsWith("Windows"),
    )
