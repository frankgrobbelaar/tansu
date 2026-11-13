package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobsTest : JobsFixture() {
    @Test
    fun `a copy puts files and folders in the other drawer and counts every byte`() = runTest {
        val jobs = jobs()
        val picks = listOf("IMG_1.jpg", "Trip").map { entry(phone, "/DCIM/Camera/$it") }

        val id = jobs.copy(picks, camera, photos, emptyMap())
        advanceUntilIdle()

        val job = jobs.job(id)
        assertEquals(Job.Status.Done, job.status)
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
        assertEquals("three", nas.text("/Photos/Trip/IMG_3.jpg"))
        assertEquals(8L, job.progress.bytesTotal)
        assertEquals(job.progress.bytesTotal, job.progress.bytesDone)
        assertEquals(2, job.progress.itemsDone)
        assertTrue(phone.exists("/DCIM/Camera/IMG_1.jpg"))
    }

    @Test
    fun `a clash keeps both, replaces or skips, as chosen`() = runTest {
        nas.file("/Photos/IMG_1.jpg", "old one").file("/Photos/IMG_2.jpg", "old two")
        nas.file("/Photos/Trip/IMG_0.jpg", "zero")
        val jobs = jobs()
        val picks =
            listOf("IMG_1.jpg", "IMG_2.jpg", "Trip").map { entry(phone, "/DCIM/Camera/$it") }
        val clashes =
            mapOf("IMG_1.jpg" to Clash.KeepBoth, "IMG_2.jpg" to Clash.Skip, "Trip" to Clash.Replace)

        val job = jobs.job(jobs.copy(picks, camera, photos, clashes)).also { advanceUntilIdle() }

        assertEquals("old one", nas.text("/Photos/IMG_1.jpg"))
        assertEquals("one", nas.text("/Photos/IMG_1 (1).jpg"))
        assertEquals("old two", nas.text("/Photos/IMG_2.jpg"))
        // Replacing a folder merges into it.
        assertEquals("zero", nas.text("/Photos/Trip/IMG_0.jpg"))
        assertEquals("three", nas.text("/Photos/Trip/IMG_3.jpg"))
        val done = jobs.job(job.id)
        assertEquals(1, done.keptBoth)
        assertEquals(1, done.skipped)
    }

    @Test
    fun `a move to another drawer removes each original once its copy checks out`() = runTest {
        val jobs = jobs()
        val picks = listOf("IMG_1.jpg", "Trip").map { entry(phone, "/DCIM/Camera/$it") }

        jobs.move(picks, camera, photos, emptyMap())
        advanceUntilIdle()

        assertEquals("three", nas.text("/Photos/Trip/IMG_3.jpg"))
        assertFalse(phone.exists("/DCIM/Camera/IMG_1.jpg"))
        assertFalse(phone.exists("/DCIM/Camera/Trip"))
        assertTrue(phone.exists("/DCIM/Camera/IMG_2.jpg"))
    }

    @Test
    fun `a move within a drawer is a rename`() = runTest {
        phone.folderAt("/Pictures")
        val jobs = jobs()
        val pictures = Where("phone", "/Pictures", "Phone", "Phone › Pictures")

        val id = jobs.move(listOf(entry(phone, "/DCIM/Camera/Trip")), camera, pictures, emptyMap())
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertEquals("three", phone.text("/Pictures/Trip/IMG_3.jpg"))
        assertFalse(phone.exists("/DCIM/Camera/Trip"))
    }

    @Test
    fun `a folder can't go into itself`() = runTest {
        val jobs = jobs()
        val inside = Where("phone", "/DCIM/Camera/Trip", "Phone", "Phone › Trip")

        val id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/Trip")), camera, inside, emptyMap())
        advanceUntilIdle()

        assertEquals(Failure.Reason.IntoItself, jobs.job(id).failures.single().reason)
        assertEquals(
            setOf(
                "/DCIM",
                "/DCIM/Camera",
                "/DCIM/Camera/IMG_1.jpg",
                "/DCIM/Camera/IMG_2.jpg",
                "/DCIM/Camera/Trip",
                "/DCIM/Camera/Trip/IMG_3.jpg",
            ),
            phone.paths(),
        )
    }

    @Test
    fun `a delete goes deepest first, and a file it can't delete is listed while the rest go`() =
        runTest {
            val jobs = jobs()
            val picks =
                listOf("IMG_1.jpg", "IMG_2.jpg", "Trip").map { entry(phone, "/DCIM/Camera/$it") }
            phone.failures["/DCIM/Camera/IMG_2.jpg"] = FileSystemException.AccessDenied("IMG_2.jpg")

            val id = jobs.delete(picks, camera)
            advanceUntilIdle()

            val job = jobs.job(id)
            assertEquals(Job.Status.Done, job.status)
            assertEquals(listOf("IMG_2.jpg"), job.failures.map { it.name })
            assertEquals(Failure.Reason.AccessDenied, job.failures.single().reason)
            assertFalse(phone.exists("/DCIM/Camera/Trip"))
            assertFalse(phone.exists("/DCIM/Camera/IMG_1.jpg"))
        }

    @Test
    fun `cancelling keeps what arrived and leaves nothing half copied`() = runTest {
        val jobs = jobs()
        val held = CompletableDeferred<Unit>()
        phone.gates["/DCIM/Camera/IMG_2.jpg"] = held
        val picks = listOf("IMG_1.jpg", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }

        val id = jobs.copy(picks, camera, photos, emptyMap())
        runCurrent()
        jobs.cancel(id)
        advanceUntilIdle()

        assertEquals(Job.Status.Cancelled, jobs.job(id).status)
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
        assertNull(nas.text("/Photos/IMG_2.jpg"))
    }

    @Test
    fun `pausing and resuming carries on without doing anything twice`() = runTest {
        val jobs = jobs()
        val held = CompletableDeferred<Unit>()
        phone.gates["/DCIM/Camera/IMG_2.jpg"] = held
        val picks = listOf("IMG_1.jpg", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }

        val id = jobs.copy(picks, camera, photos, emptyMap())
        runCurrent()
        jobs.pause(id)
        advanceUntilIdle()
        assertEquals(Job.Status.Paused(), jobs.job(id).status)

        nas.file("/Photos/IMG_1.jpg", "changed since")
        jobs.resume(id)
        advanceUntilIdle()

        val job = jobs.job(id)
        assertEquals(Job.Status.Done, job.status)
        assertEquals("changed since", nas.text("/Photos/IMG_1.jpg"))
        assertEquals("two!", nas.text("/Photos/IMG_2.jpg"))
        assertFalse(nas.exists("/Photos/IMG_1 (1).jpg"))
    }

    @Test
    fun `a server that stops answering pauses the job after half a minute, naming it`() = runTest {
        val jobs = jobs()
        nas.failures["/Photos"] = FileSystemException.Unreachable("nas")

        val id =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()

        assertEquals(Job.Status.Paused(lost = "NAS"), jobs.job(id).status)
        assertTrue(testScheduler.currentTime >= JobRun.RETRY_FOR_MILLIS)

        nas.failures.clear()
        jobs.resume(id)
        advanceUntilIdle()
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `retry runs just what failed again`() = runTest {
        nas.failures["/Photos/IMG_2.jpg"] = FileSystemException.NoSpace("IMG_2.jpg")
        val jobs = jobs()
        val picks = listOf("IMG_1.jpg", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }

        val id = jobs.copy(picks, camera, photos, emptyMap())
        advanceUntilIdle()
        assertEquals(Failure.Reason.NoSpace, jobs.job(id).failures.single().reason)

        nas.failures.clear()
        nas.file("/Photos/IMG_1.jpg", "kept as it arrived")
        jobs.retry(id)
        advanceUntilIdle()

        val job = jobs.job(id)
        assertTrue(job.failures.isEmpty())
        assertEquals("two!", nas.text("/Photos/IMG_2.jpg"))
        assertEquals("kept as it arrived", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `jobs run one at a time, in order`() = runTest {
        val jobs = jobs()
        val held = CompletableDeferred<Unit>()
        phone.gates["/DCIM/Camera/IMG_1.jpg"] = held

        val first =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        val second =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_2.jpg")), camera, photos, emptyMap())
        runCurrent()

        assertEquals(Job.Status.Running, jobs.job(first).status)
        assertEquals(Job.Status.Queued, jobs.job(second).status)
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(Job.Status.Done, jobs.job(second).status)
    }

    @Test
    fun `free names count up before the extension`() {
        assertEquals("IMG (1).jpg", freeName("IMG.jpg", isFolder = false, taken = setOf("IMG.jpg")))
        assertEquals(
            "IMG (2).jpg",
            freeName("IMG.jpg", isFolder = false, taken = setOf("img.jpg", "img (1).jpg")),
        )
        assertEquals("Trip.2025 (1)", freeName("Trip.2025", isFolder = true, taken = emptySet()))
    }

    // What the review of iterations 3 to 5 found

    @Test
    fun `a retried delete passes over a folder that's gone, and the queue goes on`() = runTest {
        val jobs = jobs()
        val picks = listOf("Trip", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }
        phone.failures["/DCIM/Camera/IMG_2.jpg"] = FileSystemException.AccessDenied("IMG_2.jpg")
        val id = jobs.delete(picks, camera)
        advanceUntilIdle()
        assertFalse(phone.exists("/DCIM/Camera/Trip"))

        phone.failures.clear()
        jobs.retry(id)
        val next =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertFalse(phone.exists("/DCIM/Camera/IMG_2.jpg"))
        assertEquals(Job.Status.Done, jobs.job(next).status)
    }

    @Test
    fun `a folder that can't be read or made fails on its own, and the queue goes on`() = runTest {
        phone.file("/DCIM/Camera/Locked/secret.jpg", "no")
        val jobs = jobs()
        val picks = listOf("IMG_1.jpg", "Locked", "Trip").map { entry(phone, "/DCIM/Camera/$it") }
        phone.failures["/DCIM/Camera/Locked"] = FileSystemException.AccessDenied("Locked")
        nas.failures["/Photos/Trip"] = FileSystemException.AccessDenied("Trip")

        val id = jobs.copy(picks, camera, photos, emptyMap())
        val next =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_2.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()

        val job = jobs.job(id)
        assertEquals(Job.Status.Done, job.status)
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
        assertEquals(setOf("Locked", "Trip", "IMG_3.jpg"), job.failures.map { it.name }.toSet())
        assertTrue(job.failures.all { it.reason == Failure.Reason.AccessDenied })
        assertEquals(Job.Status.Done, jobs.job(next).status)
        assertEquals("two!", nas.text("/Photos/IMG_2.jpg"))
    }

    @Test
    fun `a folder to paste into that's gone fails every pick`() = runTest {
        val jobs = jobs()
        val gone = Where("nas", "/Gone", "NAS", "NAS › Gone")

        val id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, gone, emptyMap())
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertEquals(Failure.Reason.NotFound, jobs.job(id).failures.single().reason)
    }

    @Test
    fun `names that differ only in case clash where case is ignored`() = runTest {
        share.file("/Media/img_1.JPG", "theirs")
        nas.file("/Photos/Pair/a.jpg", "lower").file("/Photos/Pair/A.jpg", "upper")
        val jobs = jobs()

        jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, media, emptyMap())
        jobs.copy(listOf(entry(nas, "/Photos/Pair")), photos, media, emptyMap())
        advanceUntilIdle()

        assertEquals("theirs", share.text("/Media/img_1.JPG"))
        assertEquals("one", share.text("/Media/IMG_1 (1).jpg"))
        // Two names that only SFTP tells apart both arrive, one kept under a new name.
        val pair = share.paths().filter { it.startsWith("/Media/Pair/") }
        assertEquals(setOf("lower", "upper"), pair.map { share.text(it) }.toSet())
    }

    @Test
    fun `a refused write leaves the file that was there, even after a cancel`() = runTest {
        nas.file("/Photos/IMG_1.jpg", "theirs")
        nas.readOnly += "/Photos"
        val held = CompletableDeferred<Unit>()
        phone.gates["/DCIM/Camera/IMG_2.jpg"] = held
        val jobs = jobs()
        val picks = listOf("IMG_1.jpg", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }

        val id = jobs.copy(picks, camera, photos, mapOf("IMG_1.jpg" to Clash.Replace))
        runCurrent()
        jobs.cancel(id)
        advanceUntilIdle()

        assertEquals(Job.Status.Cancelled, jobs.job(id).status)
        assertEquals("theirs", nas.text("/Photos/IMG_1.jpg"))
        assertEquals(setOf("/Photos", "/Photos/IMG_1.jpg"), nas.paths())
    }

    @Test
    fun `a file that changed after Replace was chosen is kept, beside the copy`() = runTest {
        nas.file("/Photos/IMG_1.jpg", "theirs")
        val held = CompletableDeferred<Unit>()
        phone.gates["/DCIM/Camera/IMG_1.jpg"] = held
        val jobs = jobs()

        val pick = entry(phone, "/DCIM/Camera/IMG_1.jpg")
        jobs.copy(listOf(pick), camera, photos, mapOf("IMG_1.jpg" to Clash.Replace))
        runCurrent()
        // Saved again on the server as the copy was on its way, as days later can't be ruled out.
        nas.file("/Photos/IMG_1.jpg", "theirs, newer")
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals("theirs, newer", nas.text("/Photos/IMG_1.jpg"))
        assertEquals("one", nas.text("/Photos/IMG_1 (1).jpg"))
    }

    @Test
    fun `a replace keeps the original until the new copy is whole`() = runTest {
        nas.file("/Photos/IMG_1.jpg", "theirs")
        val jobs = jobs()
        val pick = entry(phone, "/DCIM/Camera/IMG_1.jpg")
        phone.broken = true

        val id = jobs.copy(listOf(pick), camera, photos, mapOf("IMG_1.jpg" to Clash.Replace))
        advanceUntilIdle()
        assertEquals(Job.Status.Paused(lost = "NAS"), jobs.job(id).status)
        assertEquals("theirs", nas.text("/Photos/IMG_1.jpg"))

        phone.broken = false
        jobs.resume(id)
        advanceUntilIdle()
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
        assertEquals(setOf("/Photos", "/Photos/IMG_1.jpg"), nas.paths())
    }

    @Test
    fun `cancelling a paused job removes what it half wrote, and only that`() = runTest {
        nas.file("/Photos/IMG_2.jpg", "theirs")
        val jobs = jobs()
        val picks = listOf("IMG_1.jpg", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }
        phone.broken = true

        val id = jobs.copy(picks, camera, photos, mapOf("IMG_2.jpg" to Clash.Replace))
        advanceUntilIdle()
        assertEquals(Job.Status.Paused(lost = "NAS"), jobs.job(id).status)
        // Half written under a hidden name of the job's own, never under the real one.
        assertFalse(nas.exists("/Photos/IMG_1.jpg"))
        assertTrue(nas.paths().any { it.startsWith("/Photos/.tansu-") })

        jobs.cancel(id)
        advanceUntilIdle()
        assertEquals(setOf("/Photos", "/Photos/IMG_2.jpg"), nas.paths())
        assertEquals("theirs", nas.text("/Photos/IMG_2.jpg"))
    }

    @Test
    fun `a move within a drawer is still a rename after a pause`() = runTest {
        phone.folderAt("/Pictures")
        val pictures = Where("phone", "/Pictures", "Phone", "Phone › Pictures")
        val jobs = jobs()
        val pick = entry(phone, "/DCIM/Camera/Trip")
        phone.failures["/DCIM/Camera/Trip"] = FileSystemException.Unreachable("phone")

        val id = jobs.move(listOf(pick), camera, pictures, emptyMap())
        advanceUntilIdle()
        assertEquals(Job.Status.Paused(lost = "Phone"), jobs.job(id).status)

        phone.failures.clear()
        jobs.resume(id)
        advanceUntilIdle()
        assertEquals("three", phone.text("/Pictures/Trip/IMG_3.jpg"))
        assertFalse(phone.exists("/DCIM/Camera/Trip"))
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `deleting a link leaves what it points to`() = runTest {
        nas.file("/Library/film.mkv", "film").file("/Photos/Old/a.jpg", "a")
        nas.link("/Photos/media", "/Library").link("/Photos/Old/also", "/Library")
        val jobs = jobs()
        val picks = listOf("media", "Old").map { entry(nas, "/Photos/$it") }

        val id = jobs.delete(picks, photos)
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals(setOf("/Library", "/Library/film.mkv", "/Photos"), nas.paths())
    }

    @Test
    fun `moving a link to another drawer copies what's in it and keeps the originals`() = runTest {
        nas.file("/Library/film.mkv", "film").link("/Photos/media", "/Library")
        phone.folderAt("/Movies")
        val movies = Where("phone", "/Movies", "Phone", "Phone › Movies")
        val jobs = jobs()

        jobs.move(listOf(entry(nas, "/Photos/media")), photos, movies, emptyMap())
        advanceUntilIdle()

        assertEquals("film", phone.text("/Movies/media/film.mkv"))
        assertEquals("film", nas.text("/Library/film.mkv"))
        assertFalse(nas.exists("/Photos/media"))
    }

    @Test
    fun `a big file that breaks minutes in is tried again, not paused at once`() = runTest {
        // Its stream breaks as Tailscale moves from Wi-Fi to mobile data, long after it began.
        var broke = false
        val slow =
            object : FileSystem by phone {
                override suspend fun read(path: String, offset: Long): InputStream {
                    if (broke) return phone.read(path, offset)
                    broke = true
                    delay(MINUTES_IN)
                    return object : InputStream() {
                        override fun read(): Int = throw IOException("Connection reset")
                    }
                }
            }
        drawers.value += Drawer("slow", "Slow", Drawer.Kind.Phone, "/", slow)
        val from = Where("slow", "/DCIM/Camera", "Slow", "Slow › DCIM › Camera")
        val jobs = jobs()

        val id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), from, photos, emptyMap())
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `a picked folder's files are listed in its job, each done as it goes`() = runTest {
        phone.file("/DCIM/Camera/Trip/Day 2/IMG_4.jpg", "four")
        val jobs = jobs()

        val id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/Trip")), camera, photos, emptyMap())
        advanceUntilIdle()

        val job = jobs.job(id)
        val inside = job.inside.getValue("/DCIM/Camera/Trip").map { it.path }
        val expected = listOf("/DCIM/Camera/Trip/Day 2/IMG_4.jpg", "/DCIM/Camera/Trip/IMG_3.jpg")
        assertEquals(expected, inside.sorted())
        assertTrue(inside.all { it in job.done })
    }

    @Test
    fun `a full disk fails the file at once, without waiting it out`() = runTest {
        nas.full = true
        val jobs = jobs()

        val id =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()

        val job = jobs.job(id)
        assertEquals(Job.Status.Done, job.status)
        assertEquals(Failure.Reason.NoSpace, job.failures.single().reason)
        assertTrue(testScheduler.currentTime < JobRun.RETRY_FOR_MILLIS)
        assertEquals(setOf("/Photos"), nas.paths())
    }

    private companion object {
        const val MINUTES_IN = 3 * 60_000L
    }
}
