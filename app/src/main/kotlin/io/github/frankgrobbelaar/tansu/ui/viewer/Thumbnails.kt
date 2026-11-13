package io.github.frankgrobbelaar.tansu.ui.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import coil3.disk.DiskCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The shorter side of the thumbnails tansu makes, in pixels. A grid tile on a Galaxy S24 is about
 * 315 × 293, and a tile crops its picture to fill it, so this fills one with room to spare.
 */
internal const val THUMBNAIL_SIDE = 384

/**
 * The shorter side a camera's own thumbnail needs to stand in for its photo. A phone's fills a
 * tile: Samsung's are 512 × 384, or 512 × 288 for 16:9. A DSLR's 160 × 120 would be a blur.
 */
private const val CAMERA_THUMBNAIL_SIDE = 288

/**
 * The thumbnail a camera put in a JPEG's EXIF data, turned the way the photo is meant to be seen.
 * Only the start of [stream] is read, up to the photo's frame header. Null when there's none fit to
 * stand in for the photo: too small for a tile, or another shape, as when black bars pad a 3:2
 * photo's into 4:3.
 */
internal fun exifThumbnail(stream: InputStream): Bitmap? {
    val exif = jpegHead(stream)?.let { ExifInterface(ByteArrayInputStream(it)) } ?: return null
    val thumbnail = exif.thumbnailBitmap ?: return null
    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
    val fits = minOf(thumbnail.width, thumbnail.height) >= CAMERA_THUMBNAIL_SIDE
    if (!fits || !thumbnail.shaped(width, height)) return null
    return thumbnail.turned(exif.rotationDegrees, exif.isFlipped)
}

/**
 * The picture in [file], decoded straight to thumbnail size and turned the right way up: anything
 * Android reads, HEIC, PNG and WebP among them, and a moving picture's first frame.
 */
internal fun smallPicture(file: File): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        // In software, so it can be written to the cache.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        val scale = minOf(1f, THUMBNAIL_SIDE.toFloat() / minOf(width, height))
        decoder.setTargetSize((width * scale).roundToInt(), (height * scale).roundToInt())
    }

/** Keeps [thumbnail] under [key] as a small JPEG: whole, or not at all. */
internal fun DiskCache.keep(key: String, thumbnail: Bitmap) {
    // Null while the same thumbnail is in use elsewhere, as when another request has just made it.
    val editor = openEditor(key) ?: return
    var kept = false
    try {
        val written =
            fileSystem.write(editor.data) {
                thumbnail.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream())
            }
        if (written) {
            editor.commit()
            kept = true
        }
    } finally {
        // Anything short of a whole thumbnail is thrown away, never left for the next visit.
        if (!kept) editor.abort()
    }
}

/**
 * The start of a JPEG, up to its frame header (which gives the photo's size), ended with an end
 * marker: all ExifInterface needs, since the EXIF data comes before it. Only that much is read.
 * Null when it isn't a JPEG, or has no frame header within [HEAD_LIMIT] bytes.
 */
private fun jpegHead(stream: InputStream): ByteArray? =
    try {
        headOf(DataInputStream(InPieces(stream)))
    } catch (_: EOFException) {
        // It ends before its picture does: there's nothing in it to trust.
        null
    }

private fun headOf(input: DataInputStream): ByteArray? {
    if (input.readUnsignedShort() != START) return null
    val head = ByteArrayOutputStream()
    val output = DataOutputStream(head).apply { writeShort(START) }
    do {
        val marker = input.readUnsignedShort()
        // Each segment's length counts its own two bytes.
        val length = input.readUnsignedShort()
        val fits = length >= 2 && head.size() + length <= HEAD_LIMIT
        if (!isSegment(marker) || !fits) return null
        output.writeShort(marker)
        output.writeShort(length)
        output.write(ByteArray(length - 2).also(input::readFully))
    } while (marker !in FRAMES)
    output.writeShort(END)
    return head.toByteArray()
}

/** A marker with a segment after it, before the picture data starts. */
private fun isSegment(marker: Int) =
    marker shr Byte.SIZE_BITS == PREFIX && marker != SCAN && marker != END

/**
 * A stream read a few kilobytes at a time. A server that reads ahead, as SFTP does, asks for more
 * of the file in pieces as big as the read it's given, so one read of a whole EXIF segment would
 * bring down far more of the photo than its start.
 */
private class InPieces(stream: InputStream) : FilterInputStream(stream) {
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, minOf(len, HEAD_PIECE))
}

/** Whether this has the shape of a [width] × [height] photo, give or take rounding. */
private fun Bitmap.shaped(width: Int, height: Int): Boolean =
    width > 0 &&
        height > 0 &&
        abs(this.width.toFloat() / this.height * height / width - 1) <= SHAPE_TOLERANCE

/**
 * This, flipped and turned clockwise by [rotation] degrees, in that order, as a photo's EXIF data
 * means them, so it shows the right way up.
 */
private fun Bitmap.turned(rotation: Int, flipped: Boolean): Bitmap {
    if (rotation == 0 && !flipped) return this
    val matrix =
        Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            postRotate(rotation.toFloat())
        }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** How far into a JPEG its frame header is looked for: room for any phone's EXIF data, and more. */
private const val HEAD_LIMIT = 256 * 1024

private const val HEAD_PIECE = 8 * 1024

/** How much a thumbnail's shape can differ from its photo's before it's taken to be padded. */
private const val SHAPE_TOLERANCE = 0.02f

private const val JPEG_QUALITY = 85

// JPEG's markers: the file's start and end, the picture data's start, and what every marker
// starts with.
private const val START = 0xFFD8
private const val END = 0xFFD9
private const val SCAN = 0xFFDA
private const val PREFIX = 0xFF

/** The frame headers, which give the photo's size: SOF0 to SOF15, less three that aren't. */
private val FRAMES = (0xFFC0..0xFFCF).toSet() - setOf(0xFFC4, 0xFFC8, 0xFFCC)
