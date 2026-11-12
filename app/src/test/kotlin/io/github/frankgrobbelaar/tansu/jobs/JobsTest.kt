package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.MemoryFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobsTest {
    private val phone = MemoryFileSystem()
    private val nas = MemoryFileSystem()
    private val share = MemoryFileSystem(ignoreCase = true)
    private val drawers =
        MutableStateFlow(
            listOf(
                Drawer("phone", "Phone", Drawer.Kind.Phone, "/", phone),
                Drawer("nas", "NAS", Drawer.Kind.Sftp, "/", nas),
                Drawer("share", "Share", Drawer.Kind.Smb, "/", share),
            )
        )
    private val camera = Where("phone", "/DCIM/Camera", "Phone", "Phone › DCIM › Camera")
    private val photos = Where("nas", "/Photos", "NAS", "NAS › Photos")
    private val media = Where("share", "/Media", "Share", "Share › Media")

    private fun TestScope.jobs() =
        Jobs(
            drawers = drawers,
            // Not backgroundScope: advanceUntilIdle leaves background work waiting.
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            io = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

    private fun entry(files: MemoryFileSystem, path: String): Entry = runBlocking {
        checkNotNull(files.stat(path))
    }

    private fun Jobs.job(id: Long) = all.value.single { it.id == id }

    init {
        phone.file("/DCIM/Camera/IMG_1.jpg", "one").file("/DCIM/Camera/IMG_2.jpg", "two!")
        phone.file("/DCIM/Camera/Trip/IMG_3.jpg", "three")
        nas.folderAt("/Photos")
        share.folderAt("/Media")
    }

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
        assertTrue(nas.paths().any { it.startsWith("/Photos/.IMG_1.jpg.") })

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

    // What the review of the safety fixes found

    @Test
    fun `a file that arrives while a job is paused is kept beside, not written over`() = runTest {
        val jobs = jobs()
        val picks = listOf("IMG_1.jpg", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }
        phone.broken = true
        val id = jobs.copy(picks, camera, photos, emptyMap())
        advanceUntilIdle()
        assertEquals(Job.Status.Paused(lost = "NAS"), jobs.job(id).status)

        // Another job moves a different IMG_2.jpg in while this one waits.
        nas.file("/Photos/IMG_2.jpg", "other two")
        phone.broken = false
        jobs.resume(id)
        advanceUntilIdle()

        assertEquals("other two", nas.text("/Photos/IMG_2.jpg"))
        assertEquals("two!", nas.text("/Photos/IMG_2 (1).jpg"))
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `two names that differ only in case both survive a merge where case is ignored`() =
        runTest {
            share.folderAt("/Media/Pair")
            nas.file("/Photos/Pair/a.jpg", "lower").file("/Photos/Pair/A.jpg", "upper")
            val jobs = jobs()

            jobs.move(
                listOf(entry(nas, "/Photos/Pair")),
                photos,
                media,
                mapOf("Pair" to Clash.Replace),
            )
            advanceUntilIdle()

            val pair = share.paths().filter { it.startsWith("/Media/Pair/") }
            assertEquals(setOf("lower", "upper"), pair.map { share.text(it) }.toSet())
        }

    @Test
    fun `cancelling a paused job never removes a file someone else put there`() = runTest {
        val jobs = jobs()
        phone.broken = true
        val id =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()
        assertEquals(Job.Status.Paused(lost = "NAS"), jobs.job(id).status)

        nas.file("/Photos/IMG_1.jpg", "someone else's")
        jobs.cancel(id)
        advanceUntilIdle()

        assertEquals(setOf("/Photos", "/Photos/IMG_1.jpg"), nas.paths())
        assertEquals("someone else's", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `cancelling a job that was resumed but still waits removes what it half wrote`() = runTest {
        val jobs = jobs()
        phone.broken = true
        val first =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()
        phone.broken = false
        val held = CompletableDeferred<Unit>()
        phone.gates["/DCIM/Camera/IMG_2.jpg"] = held
        jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_2.jpg")), camera, photos, emptyMap())
        runCurrent()

        jobs.resume(first)
        assertEquals(Job.Status.Queued, jobs.job(first).status)
        jobs.cancel(first)
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals(setOf("/Photos", "/Photos/IMG_2.jpg"), nas.paths())
    }

    @Test
    fun `a move that stopped after its copy checks that copy instead of making another`() =
        runTest {
            val jobs = jobs()
            phone.deleteFailures["/DCIM/Camera/IMG_1.jpg"] = FileSystemException.Unreachable("x")
            val id =
                jobs.move(
                    listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")),
                    camera,
                    photos,
                    emptyMap(),
                )
            advanceUntilIdle()
            assertTrue(jobs.job(id).status is Job.Status.Paused)

            phone.deleteFailures.clear()
            jobs.resume(id)
            advanceUntilIdle()

            assertEquals(setOf("/Photos", "/Photos/IMG_1.jpg"), nas.paths())
            assertFalse(phone.exists("/DCIM/Camera/IMG_1.jpg"))
        }

    @Test
    fun `a job whose failures trying again can't fix offers no retry`() = runTest {
        nas.file("/Photos/Old/a.jpg", "a").link("/Photos/Old/also", "/Photos")
        phone.folderAt("/Backup")
        val jobs = jobs()
        val backup = Where("phone", "/Backup", "Phone", "Phone › Backup")

        val id = jobs.copy(listOf(entry(nas, "/Photos/Old")), photos, backup, emptyMap())
        advanceUntilIdle()

        assertEquals(Failure.Reason.Link, jobs.job(id).failures.single().reason)
        assertFalse(jobs.job(id).canRetry)
    }

    @Test
    fun `saving shared files can't be retried once the app that shared them is gone`() = runTest {
        val jobs = jobs()
        val lent = MemoryFileSystem().file("/IMG_9.jpg", "nine")
        lent.failures["/IMG_9.jpg"] = FileSystemException.AccessDenied("IMG_9.jpg")
        val from = Where("shared-1", "", "Gallery", "Gallery")
        val pick = Entry("IMG_9.jpg", "/IMG_9.jpg", isFolder = false, size = 4, modified = 0)

        val id = jobs.copyIn(lent, listOf(pick), from, photos)
        advanceUntilIdle()

        assertEquals(Failure.Reason.AccessDenied, jobs.job(id).failures.single().reason)
        assertFalse(jobs.job(id).canRetry)
    }
}
