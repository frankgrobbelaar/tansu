package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.nameOf
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A big file whose copy broke off carries on from where it was, rather than starting again. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarryOnTest : JobsFixture() {
    private val film = "0123456789abcdef".repeat(BIG / SIXTEEN)

    init {
        phone.file("/DCIM/Camera/film.mp4", film)
    }

    /**
     * The phone's camera folder, its reads broken off once [breakAt] bytes in, as by Wi-Fi going.
     */
    private fun breaking(breakAt: Long, then: () -> Unit = {}): Where {
        var breaks = true
        val flaky =
            object : FileSystem by phone {
                override suspend fun read(path: String, offset: Long): InputStream {
                    val stream = phone.read(path, offset)
                    if (!breaks) return stream
                    breaks = false
                    return object : FilterInputStream(stream) {
                        var given = offset

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (given >= breakAt) {
                                then()
                                throw IOException("Connection reset")
                            }
                            val read =
                                super.read(b, off, minOf(len.toLong(), breakAt - given).toInt())
                            if (read > 0) given += read
                            return read
                        }
                    }
                }
            }
        drawers.value += Drawer("flaky", "Flaky", Drawer.Kind.Phone, "/", flaky)
        return Where("flaky", "/DCIM/Camera", "Flaky", "Flaky › DCIM › Camera")
    }

    @Test
    fun `a big file whose copy breaks off carries on from where it was`() = runTest {
        val jobs = jobs()

        val id =
            jobs.copy(
                listOf(entry(phone, "/DCIM/Camera/film.mp4")),
                breaking(BREAK),
                photos,
                emptyMap(),
            )
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertEquals(film, nas.text("/Photos/film.mp4"))
        // The last stretch before the break is written again, in case it was left with gaps.
        assertEquals(listOf(BREAK - JobRun.WRITTEN_AGAIN), nas.writeOffsets)
        assertEquals(listOf(0L, BREAK - JobRun.WRITTEN_AGAIN), phone.readOffsets)
        assertEquals(BIG.toLong(), jobs.job(id).progress.bytesDone)
    }

    @Test
    fun `a paused big file carries on from where it was when the job resumes`() = runTest {
        var id = 0L
        val jobs = jobs()
        val from = breaking(BREAK) { jobs.pause(id) }

        id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/film.mp4")), from, photos, emptyMap())
        runCurrent()
        // It stops with its half kept, hidden.
        yield()
        advanceUntilIdle()
        assertTrue(jobs.job(id).status is Job.Status.Paused)
        assertTrue(nas.paths().any { nameOf(it).startsWith(".tansu-") })

        jobs.resume(id)
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertEquals(film, nas.text("/Photos/film.mp4"))
        assertEquals(listOf(BREAK - JobRun.WRITTEN_AGAIN), nas.writeOffsets)
        assertEquals(setOf("/Photos", "/Photos/film.mp4"), nas.paths())
    }

    @Test
    fun `a big file that changed while the job was paused starts again`() = runTest {
        var id = 0L
        var edits = 0L
        val jobs = jobs()
        val from = breaking(BREAK) { jobs.pause(id) }
        // Says when it last changed, as a file someone edits does.
        val edited =
            object : FileSystem by checkNotNull(drawers.value.last().fileSystem) {
                override suspend fun stat(path: String) = phone.stat(path)?.copy(modified = edits)
            }
        drawers.value =
            drawers.value.dropLast(1) + Drawer("flaky", "Flaky", Drawer.Kind.Phone, "/", edited)

        id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/film.mp4")), from, photos, emptyMap())
        runCurrent()
        yield()
        advanceUntilIdle()
        val changed = film.reversed()
        phone.file("/DCIM/Camera/film.mp4", changed)
        edits++
        jobs.resume(id)
        advanceUntilIdle()

        assertEquals(changed, nas.text("/Photos/film.mp4"))
        assertTrue(nas.writeOffsets.isEmpty())
    }

    @Test
    fun `cancelling a paused job removes the half it kept`() = runTest {
        var id = 0L
        val jobs = jobs()
        val from = breaking(BREAK) { jobs.pause(id) }

        id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/film.mp4")), from, photos, emptyMap())
        runCurrent()
        yield()
        advanceUntilIdle()
        jobs.cancel(id)
        advanceUntilIdle()

        assertEquals(setOf("/Photos"), nas.paths())
    }

    @Test
    fun `a half of another file whose hidden name is the same isn't carried on with`() = runTest {
        // "Aa" and "BB" hash alike, so their paths' hidden names are one and the same.
        val other = film.reversed()
        phone.file("/DCIM/Camera/Aa.mp4", film).file("/DCIM/Camera/BB.mp4", other)
        var id = 0L
        var refused = true
        val jobs = jobs()
        val flaky =
            object : FileSystem by phone {
                var breaks = true

                override suspend fun read(path: String, offset: Long): InputStream {
                    // Aa can't be read the first time; BB breaks off part-way, and the job pauses.
                    if (path.endsWith("Aa.mp4") && refused) {
                        refused = false
                        throw FileSystemException.AccessDenied(path)
                    }
                    val stream = phone.read(path, offset)
                    if (!path.endsWith("BB.mp4") || !breaks) return stream
                    breaks = false
                    return cutOff(stream, offset, BREAK) { jobs.pause(id) }
                }
            }
        drawers.value += Drawer("flaky", "Flaky", Drawer.Kind.Phone, "/", flaky)
        val from = Where("flaky", "/DCIM/Camera", "Flaky", "Flaky › DCIM › Camera")
        val picks = listOf("Aa.mp4", "BB.mp4").map { entry(phone, "/DCIM/Camera/$it") }

        id = jobs.copy(picks, from, photos, emptyMap())
        advanceUntilIdle()
        jobs.resume(id)
        advanceUntilIdle()
        if (jobs.job(id).canRetry) jobs.retry(id)
        advanceUntilIdle()

        assertEquals(film, nas.text("/Photos/Aa.mp4"))
        assertEquals(other, nas.text("/Photos/BB.mp4"))
    }

    @Test
    fun `a half kept for a file that's gone when the job resumes doesn't stay behind`() = runTest {
        phone.file("/DCIM/Camera/Trip/film.mp4", film)
        var id = 0L
        val jobs = jobs()
        val flaky =
            object : FileSystem by phone {
                var breaks = true

                override suspend fun read(path: String, offset: Long): InputStream {
                    val stream = phone.read(path, offset)
                    if (!path.endsWith("film.mp4") || !breaks) return stream
                    breaks = false
                    return cutOff(stream, offset, BREAK) { jobs.pause(id) }
                }
            }
        drawers.value += Drawer("flaky", "Flaky", Drawer.Kind.Phone, "/", flaky)
        val from = Where("flaky", "/DCIM/Camera", "Flaky", "Flaky › DCIM › Camera")

        id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/Trip")), from, photos, emptyMap())
        advanceUntilIdle()
        phone.delete("/DCIM/Camera/Trip/film.mp4")
        jobs.resume(id)
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertTrue(nas.paths().none { nameOf(it).startsWith(".tansu-") })
    }

    @Test
    fun `a source that shrank as the job carried on fails the file, not a stale tail`() = runTest {
        var id = 0L
        var reads = 0
        val jobs = jobs()
        val from = breaking(BREAK) { jobs.pause(id) }
        val flaky = checkNotNull(drawers.value.last().fileSystem)
        // Cut short in place just as the job carries on, keeping when it last changed.
        val shrinking =
            object : FileSystem by flaky {
                override suspend fun read(path: String, offset: Long): InputStream {
                    if (++reads == 2) phone.file(path, film.take(SHORTER))
                    return flaky.read(path, offset)
                }
            }
        drawers.value =
            drawers.value.dropLast(1) + Drawer("flaky", "Flaky", Drawer.Kind.Phone, "/", shrinking)

        id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/film.mp4")), from, photos, emptyMap())
        advanceUntilIdle()
        jobs.resume(id)
        advanceUntilIdle()

        assertEquals(Failure.Reason.Mismatch, jobs.job(id).failures.single().reason)
        assertEquals(setOf("/Photos"), nas.paths())
    }

    @Test
    fun `a server that won't write part-way into a file has it start again`() = runTest {
        val unwilling =
            object : FileSystem by nas {
                override suspend fun writeAt(path: String, offset: Long) =
                    throw FileSystemException.Unreadable(path)
            }
        drawers.value += Drawer("unwilling", "Unwilling", Drawer.Kind.Sftp, "/", unwilling)
        val to = Where("unwilling", "/Photos", "Unwilling", "Unwilling › Photos")
        val jobs = jobs()

        val id =
            jobs.copy(
                listOf(entry(phone, "/DCIM/Camera/film.mp4")),
                breaking(BREAK),
                to,
                emptyMap(),
            )
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertEquals(film, nas.text("/Photos/film.mp4"))
        assertEquals(listOf(0L, 0L), phone.readOffsets)
    }

    @Test
    fun `a link that drops now and then keeps a big file going, as each go gets further`() =
        runTest {
            var breaks = 0
            val dropping =
                object : FileSystem by phone {
                    override suspend fun read(path: String, offset: Long): InputStream {
                        // Each go takes a while to get going, then gets another stretch further.
                        delay(EACH_GO_MILLIS)
                        val stream = phone.read(path, offset)
                        if (++breaks > DROPS) return stream
                        return cutOff(stream, offset, offset + JobRun.WRITTEN_AGAIN * 2)
                    }
                }
            drawers.value += Drawer("dropping", "Dropping", Drawer.Kind.Phone, "/", dropping)
            val from = Where("dropping", "/DCIM/Camera", "Dropping", "Dropping › DCIM › Camera")
            phone.file("/DCIM/Camera/film.mp4", film.repeat(2))
            val jobs = jobs()

            val id =
                jobs.copy(listOf(entry(phone, "/DCIM/Camera/film.mp4")), from, photos, emptyMap())
            advanceUntilIdle()

            assertEquals(Job.Status.Done, jobs.job(id).status)
            assertEquals(film.repeat(2), nas.text("/Photos/film.mp4"))
            assertTrue(testScheduler.currentTime > JobRun.RETRY_FOR_MILLIS)
        }

    /** [stream], read from [offset], breaking off once [breakAt] bytes in, after [then]. */
    private fun cutOff(stream: InputStream, offset: Long, breakAt: Long, then: () -> Unit = {}) =
        object : FilterInputStream(stream) {
            var given = offset

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (given >= breakAt) {
                    then()
                    throw IOException("Connection reset")
                }
                val read = super.read(b, off, minOf(len.toLong(), breakAt - given).toInt())
                if (read > 0) given += read
                return read
            }
        }

    private companion object {
        const val SIXTEEN = 16
        const val BIG = 3 * 1024 * 1024
        const val BREAK = 5 * 512 * 1024L
        const val SHORTER = 2 * 1024 * 1024
        const val DROPS = 3
        const val EACH_GO_MILLIS = 20_000L
    }
}
