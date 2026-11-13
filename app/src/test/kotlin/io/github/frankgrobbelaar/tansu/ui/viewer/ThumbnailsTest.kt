package io.github.frankgrobbelaar.tansu.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailsTest {
    /** A camera's thumbnail, red in its top-left quarter and blue elsewhere. */
    private val marked =
        picture(512, 384) { x, y -> if (x < 256 && y < 192) Color.RED else Color.BLUE }.jpeg()

    private val photo = picture(1024, 768).jpeg()

    @Test
    fun `a camera's thumbnail is turned and flipped the way its photo is`() {
        // Where the red quarter ends up, and whether the thumbnail turns on its side.
        val expected =
            mapOf(
                ExifInterface.ORIENTATION_NORMAL to (Corner.TopLeft to false),
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (Corner.TopRight to false),
                ExifInterface.ORIENTATION_ROTATE_180 to (Corner.BottomRight to false),
                ExifInterface.ORIENTATION_FLIP_VERTICAL to (Corner.BottomLeft to false),
                ExifInterface.ORIENTATION_TRANSPOSE to (Corner.TopLeft to true),
                ExifInterface.ORIENTATION_ROTATE_90 to (Corner.TopRight to true),
                ExifInterface.ORIENTATION_TRANSVERSE to (Corner.BottomRight to true),
                ExifInterface.ORIENTATION_ROTATE_270 to (Corner.BottomLeft to true),
            )

        for ((orientation, outcome) in expected) {
            val (corner, sideways) = outcome
            val turned =
                checkNotNull(exifThumbnail(Counting(photo.withThumbnail(marked, orientation))))

            assertEquals("Orientation $orientation", if (sideways) 384 else 512, turned.width)
            assertEquals("Orientation $orientation", listOf(corner), turned.redCorners())
        }
    }

    @Test
    fun `only the start of a photo is read for its thumbnail, a few kilobytes at a time`() {
        val big = photo.withThumbnail(speckled()).padded(2_000_000)
        val stream = Counting(big)

        assertNotNull(exifThumbnail(stream))
        assertTrue("Read ${stream.consumed} of ${big.size}", stream.consumed < 64 * 1024)
        // A server that reads ahead asks for more at the size of each read.
        assertTrue("Asked for ${stream.largestRead} at once", stream.largestRead <= 8 * 1024)
    }

    @Test
    fun `a photo without a thumbnail has none, and is read only to its frame header`() {
        val stream = Counting(photo.padded(2_000_000))

        assertNull(exifThumbnail(stream))
        assertTrue("Read ${stream.consumed}", stream.consumed < 4 * 1024)
    }

    @Test
    fun `a thumbnail too small to fill a tile is passed over`() {
        val small = picture(160, 120).jpeg()

        assertNull(exifThumbnail(Counting(photo.withThumbnail(small))))
    }

    @Test
    fun `a thumbnail padded to another shape than its photo's is passed over`() {
        val threeByTwo = picture(1200, 800).jpeg()

        assertNull(exifThumbnail(Counting(threeByTwo.withThumbnail(marked))))
    }

    @Test
    fun `a file that isn't a JPEG has no camera thumbnail, and only its first bytes are read`() {
        val stream = Counting(picture(512, 384).png())

        assertNull(exifThumbnail(stream))
        assertEquals(2, stream.consumed)
    }

    @Test
    fun `a JPEG that ends before its picture starts has no camera thumbnail`() {
        val cut = photo.withThumbnail(marked).copyOfRange(0, 1000)

        assertNull(exifThumbnail(Counting(cut)))
    }

    private enum class Corner {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
    }

    /** The quarters whose middles are red. */
    private fun Bitmap.redCorners(): List<Corner> {
        val left = width / 4
        val right = width * 3 / 4
        val top = height / 4
        val bottom = height * 3 / 4
        return mapOf(
                Corner.TopLeft to getPixel(left, top),
                Corner.TopRight to getPixel(right, top),
                Corner.BottomLeft to getPixel(left, bottom),
                Corner.BottomRight to getPixel(right, bottom),
            )
            .filterValues { Color.red(it) > 200 && Color.blue(it) < 60 }
            .keys
            .toList()
    }
}
