package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.nameOf
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private companion object {
        const val SIXTEEN = 16
        const val BIG = 3 * 1024 * 1024
        const val BREAK = 5 * 512 * 1024L
    }
}
