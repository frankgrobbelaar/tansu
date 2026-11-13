package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.MemoryFileSystem
import io.github.frankgrobbelaar.tansu.files.nameOf
import io.github.frankgrobbelaar.tansu.files.parentOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jobs never lose or double a file, whatever the network and the file systems in between do. */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSafetyTest : JobsFixture() {
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
    fun `a failure says which drawer said no`() = runTest {
        phone.file("/DCIM/Camera/Locked/secret.jpg", "no")
        val jobs = jobs()
        val picks = listOf("Locked", "IMG_1.jpg").map { entry(phone, "/DCIM/Camera/$it") }
        phone.failures["/DCIM/Camera/Locked"] = FileSystemException.AccessDenied("Locked")
        nas.readOnly += "/Photos"

        val id = jobs.copy(picks, camera, photos, emptyMap())
        advanceUntilIdle()

        val failures = jobs.job(id).failures.associateBy { it.name }
        assertTrue(failures.getValue("Locked").atSource)
        assertFalse(failures.getValue("IMG_1.jpg").atSource)
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

    // What the review of the hidden names found

    @Test
    fun `a retried merge into a case-blind folder never replaces a file it moved there`() =
        runTest {
            share.folderAt("/Media/Pair")
            nas.file("/Photos/Pair/a.jpg", "lower").file("/Photos/Pair/A.jpg", "upper")
            val pair = entry(nas, "/Photos/Pair")
            nas.failures["/Photos/Pair/A.jpg"] = FileSystemException.AccessDenied("A.jpg")
            val jobs = jobs()

            val id = jobs.move(listOf(pair), photos, media, mapOf("Pair" to Clash.Replace))
            advanceUntilIdle()
            nas.failures.clear()
            jobs.retry(id)
            advanceUntilIdle()

            val texts = share.paths().filter { it.startsWith("/Media/Pair/") }.map(share::text)
            assertEquals(setOf("lower", "upper"), texts.toSet())
        }

    @Test
    fun `a move that would replace the very file it moves keeps it`() = runTest {
        nas.file("/Library/film.mkv", "film").link("/Photos/media", "/Library")
        val jobs = jobs()
        val library = Where("nas", "/Library", "NAS", "NAS › Library")
        val media = Where("nas", "/Photos/media", "NAS", "NAS › Photos › media")

        jobs.move(
            listOf(entry(nas, "/Library/film.mkv")),
            library,
            media,
            mapOf("film.mkv" to Clash.Replace),
        )
        advanceUntilIdle()

        assertEquals(listOf("film"), nas.paths().mapNotNull(nas::text))
    }

    @Test
    fun `an answer lost as a file is named doesn't make a second copy`() = runTest {
        nas.lostAnswers += "/Photos/IMG_1.jpg"
        val jobs = jobs()

        jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()

        assertEquals(setOf("/Photos", "/Photos/IMG_1.jpg"), nas.paths())
    }

    @Test
    fun `an answer lost as a moved original is removed still ends well`() = runTest {
        phone.lostAnswers += "/DCIM/Camera/IMG_1.jpg"
        val jobs = jobs()

        val id =
            jobs.move(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        advanceUntilIdle()

        assertEquals(Job.Status.Done, jobs.job(id).status)
        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `a file still being written fails as not matching, and a retry copies it whole`() =
        runTest {
            phone.growing["/DCIM/Camera/IMG_1.jpg"] = " more"
            val jobs = jobs()

            val id =
                jobs.copy(
                    listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")),
                    camera,
                    photos,
                    emptyMap(),
                )
            advanceUntilIdle()
            assertEquals(Failure.Reason.Mismatch, jobs.job(id).failures.single().reason)
            assertEquals(setOf("/Photos"), nas.paths())

            jobs.retry(id)
            advanceUntilIdle()
            assertEquals("one more", nas.text("/Photos/IMG_1.jpg"))
        }

    @Test
    fun `a cancel while a file is being opened leaves nothing behind`() = runTest {
        nas.writeGates["/Photos"] = CompletableDeferred()
        val jobs = jobs()

        val id =
            jobs.copy(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), camera, photos, emptyMap())
        runCurrent()
        jobs.cancel(id)
        advanceUntilIdle()

        assertEquals(setOf("/Photos"), nas.paths())
    }

    @Test
    fun `two folders whose names differ only in case stay apart where case is ignored`() = runTest {
        nas.file("/Photos/Pair/Sub/a.jpg", "a").file("/Photos/Pair/sub/b.jpg", "b")
        val jobs = jobs()

        jobs.copy(listOf(entry(nas, "/Photos/Pair")), photos, media, emptyMap())
        advanceUntilIdle()

        val a = share.paths().single { share.text(it) == "a" }
        val b = share.paths().single { share.text(it) == "b" }
        assertTrue(parentOf(a) != parentOf(b))
    }

    @Test
    fun `a long name still fits beside its hidden copy`() = runTest {
        val long = "x".repeat(246) + ".jpg"
        phone.file("/DCIM/Camera/$long", "long")
        val jobs = jobs()

        val id = jobs.copy(listOf(entry(phone, "/DCIM/Camera/$long")), camera, photos, emptyMap())
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals("long", nas.text("/Photos/$long"))
    }

    // What the fourth review found

    @Test
    fun `a move replaces another file the same size, as chosen`() = runTest {
        phone.file("/DCIM/Camera/same.jpg", "mine")
        nas.file("/Photos/same.jpg", "nas!")
        val jobs = jobs()
        val pick = entry(phone, "/DCIM/Camera/same.jpg")

        jobs.move(listOf(pick), camera, photos, mapOf("same.jpg" to Clash.Replace))
        advanceUntilIdle()

        assertEquals("mine", nas.text("/Photos/same.jpg"))
        assertFalse(nas.exists("/Photos/same (1).jpg"))
        assertFalse(phone.exists("/DCIM/Camera/same.jpg"))
    }

    @Test
    fun `a move between two drawers on one disk never replaces the file it moves`() = runTest {
        nas.file("/Library/film.mkv", "film")
        drawers.value += Drawer("mirror", "Mirror", Drawer.Kind.Smb, "/", nas)
        val jobs = jobs()
        val library = Where("nas", "/Library", "NAS", "NAS › Library")
        val mirrored = Where("mirror", "/Library", "Mirror", "Mirror › Library")
        val pick = entry(nas, "/Library/film.mkv")

        jobs.move(listOf(pick), library, mirrored, mapOf("film.mkv" to Clash.Replace))
        advanceUntilIdle()

        assertEquals(listOf("film"), nas.paths().mapNotNull(nas::text))
    }

    @Test
    fun `a shared file whose size the app sharing it doesn't say is saved whole`() = runTest {
        val lent = MemoryFileSystem().file("/IMG_9.jpg", "nine")
        val unsized =
            object : FileSystem by lent {
                override suspend fun stat(path: String) = lent.stat(path)?.copy(size = 0)
            }
        val jobs = jobs()
        val from = Where("shared-1", "", "Gallery", "Gallery")
        val pick = Entry("IMG_9.jpg", "/IMG_9.jpg", isFolder = false, size = 0, modified = 0)

        val id = jobs.copyIn(unsized, listOf(pick), from, photos)
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals("nine", nas.text("/Photos/IMG_9.jpg"))
    }

    @Test
    fun `a file kept beside another is named once, though the job stops as it's named`() = runTest {
        nas.file("/Photos/Pair/a.jpg", "lower").file("/Photos/Pair/A.jpg", "upper")
        share.lostAnswers += "/Media/Pair/A (1).jpg"
        val jobs = jobs()

        val id = jobs.copy(listOf(entry(nas, "/Photos/Pair")), photos, media, emptyMap())
        runCurrent()
        // Paused while it waits to hear how the naming went.
        jobs.pause(id)
        runCurrent()
        jobs.resume(id)
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals(1, jobs.job(id).keptBoth)
        assertEquals(
            setOf("/Media", "/Media/Pair", "/Media/Pair/a.jpg", "/Media/Pair/A (1).jpg"),
            share.paths(),
        )
    }

    @Test
    fun `an answer lost as a file moves within a drawer still ends well`() = runTest {
        phone.folderAt("/Pictures")
        phone.lostAnswers += "/Pictures/IMG_1.jpg"
        val jobs = jobs()
        val pictures = Where("phone", "/Pictures", "Phone", "Phone › Pictures")
        val pick = entry(phone, "/DCIM/Camera/IMG_1.jpg")

        val id = jobs.move(listOf(pick), camera, pictures, emptyMap())
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals("one", phone.text("/Pictures/IMG_1.jpg"))
    }

    @Test
    fun `a folder is done once everything in it is, and not while something in it failed`() =
        runTest {
            phone.file("/DCIM/Camera/Fine/b.jpg", "b")
            phone.failures["/DCIM/Camera/Trip/IMG_3.jpg"] = FileSystemException.AccessDenied("3")
            val jobs = jobs()
            val picks = listOf("Trip", "Fine").map { entry(phone, "/DCIM/Camera/$it") }

            val id = jobs.copy(picks, camera, photos, emptyMap())
            advanceUntilIdle()

            val done = jobs.job(id).done
            assertTrue("/DCIM/Camera/Fine" in done)
            assertFalse("/DCIM/Camera/Trip" in done)
        }

    @Test
    fun `both of two folders whose names differ only in case keep their places`() = runTest {
        nas.file("/Photos/Pair/Sub/a.jpg", "a").file("/Photos/Pair/sub/b.jpg", "b")
        val jobs = jobs()

        val id = jobs.copy(listOf(entry(nas, "/Photos/Pair")), photos, media, emptyMap())
        advanceUntilIdle()

        // Remembered both, so a resumed job that lists them the other way round agrees.
        val placed = jobs.job(id).placed
        assertTrue("/Photos/Pair/Sub" in placed && "/Photos/Pair/sub" in placed)
    }

    // What the fifth review found

    @Test
    fun `a file named as the job paused isn't copied twice when another file goes first`() =
        runTest {
            val picks = listOf("IMG_1.jpg", "IMG_2.jpg").map { entry(phone, "/DCIM/Camera/$it") }
            phone.failures["/DCIM/Camera/IMG_1.jpg"] = FileSystemException.AccessDenied("1")
            nas.lostAnswers += "/Photos/IMG_2.jpg"
            val jobs = jobs()

            val id = jobs.move(picks, camera, photos, emptyMap())
            runCurrent()
            // Paused while it waits to hear how IMG_2's naming went; IMG_1 goes first on resume.
            jobs.pause(id)
            runCurrent()
            phone.failures.clear()
            jobs.resume(id)
            advanceUntilIdle()

            assertTrue(jobs.job(id).failures.isEmpty())
            assertEquals(setOf("/Photos", "/Photos/IMG_1.jpg", "/Photos/IMG_2.jpg"), nas.paths())
            assertEquals("two!", nas.text("/Photos/IMG_2.jpg"))
            assertFalse(phone.exists("/DCIM/Camera/IMG_2.jpg"))
        }

    @Test
    fun `a move within a drawer that checks names first still ends well after a lost answer`() =
        runTest {
            nas.file("/Library/film.mkv", "film").folderAt("/Photos")
            nas.lostAnswers += "/Photos/film.mkv"
            // As SFTP does: a taken name is refused before the server looks for the file.
            val sftp =
                object : FileSystem by nas {
                    override suspend fun rename(from: String, to: String, replace: Boolean) {
                        if (!replace && nas.stat(to) != null) throw FileSystemException.Exists(to)
                        nas.rename(from, to, replace)
                    }
                }
            drawers.value += Drawer("sftp", "SFTP", Drawer.Kind.Sftp, "/", sftp)
            val jobs = jobs()
            val library = Where("sftp", "/Library", "SFTP", "SFTP › Library")
            val inPhotos = Where("sftp", "/Photos", "SFTP", "SFTP › Photos")

            val id =
                jobs.move(listOf(entry(nas, "/Library/film.mkv")), library, inPhotos, emptyMap())
            advanceUntilIdle()

            assertTrue(jobs.job(id).failures.isEmpty())
            assertEquals("film", nas.text("/Photos/film.mkv"))
        }

    @Test
    fun `a shared file that arrives short fails as not matching`() = runTest {
        val lent = MemoryFileSystem().file("/IMG_9.jpg", "nine")
        val saysMore =
            object : FileSystem by lent {
                override suspend fun stat(path: String) = lent.stat(path)?.copy(size = 9)
            }
        val jobs = jobs()
        val from = Where("shared-1", "", "Gallery", "Gallery")
        val pick = Entry("IMG_9.jpg", "/IMG_9.jpg", isFolder = false, size = 9, modified = 0)

        val id = jobs.copyIn(saysMore, listOf(pick), from, photos)
        advanceUntilIdle()

        assertEquals(Failure.Reason.Mismatch, jobs.job(id).failures.single().reason)
        assertEquals(setOf("/Photos"), nas.paths())
    }

    @Test
    fun `a moved file that changed since it was copied stays, and so does its copy`() = runTest {
        // Each look finds it changed, as a file someone's editing would be.
        var looks = 0L
        val busy =
            object : FileSystem by phone {
                override suspend fun stat(path: String) = phone.stat(path)?.copy(modified = ++looks)
            }
        drawers.value += Drawer("busy", "Busy", Drawer.Kind.Phone, "/", busy)
        val jobs = jobs()
        val busyCamera = Where("busy", "/DCIM/Camera", "Busy", "Busy › DCIM › Camera")

        val id =
            jobs.move(
                listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")),
                busyCamera,
                photos,
                emptyMap(),
            )
        advanceUntilIdle()

        assertEquals(Failure.Reason.Mismatch, jobs.job(id).failures.single().reason)
        assertEquals("one", phone.text("/DCIM/Camera/IMG_1.jpg"))
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `a moved folder that can't be removed isn't done, so a resumed job tries again`() =
        runTest {
            phone.deleteFailures["/DCIM/Camera/Trip"] = FileSystemException.AccessDenied("Trip")
            val jobs = jobs()

            val id =
                jobs.move(listOf(entry(phone, "/DCIM/Camera/Trip")), camera, photos, emptyMap())
            advanceUntilIdle()

            assertEquals("three", nas.text("/Photos/Trip/IMG_3.jpg"))
            assertTrue(phone.exists("/DCIM/Camera/Trip"))
            assertFalse("/DCIM/Camera/Trip" in jobs.job(id).done)
        }

    @Test
    fun `a moved folder whose last delete went through as the job paused ends well`() = runTest {
        phone.lostAnswers += "/DCIM/Camera/Trip"
        val jobs = jobs()

        val id = jobs.move(listOf(entry(phone, "/DCIM/Camera/Trip")), camera, photos, emptyMap())
        runCurrent()
        jobs.pause(id)
        runCurrent()
        jobs.resume(id)
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals("three", nas.text("/Photos/Trip/IMG_3.jpg"))
        assertFalse(phone.exists("/DCIM/Camera/Trip"))
    }

    // What the sixth review found

    @Test
    fun `a pause as a refused copy is cleared away can't pass for its naming`() = runTest {
        nas.file("/Photos/IMG_1.jpg", "old")
        var id = 0L
        var refusing = true
        val jobs = jobs()
        // Replacing is refused, as for a file open elsewhere, and the job is paused just as its
        // copy is cleared away.
        val busy =
            object : FileSystem by nas {
                override suspend fun rename(from: String, to: String, replace: Boolean) {
                    if (replace && refusing) throw FileSystemException.AccessDenied(to)
                    nas.rename(from, to, replace)
                }

                override suspend fun delete(path: String) {
                    nas.delete(path)
                    if (refusing && nameOf(path).startsWith(".tansu-")) {
                        jobs.pause(id)
                        yield()
                    }
                }
            }
        drawers.value += Drawer("busy", "Busy", Drawer.Kind.Smb, "/", busy)
        val busyPhotos = Where("busy", "/Photos", "Busy", "Busy › Photos")
        val pick = entry(phone, "/DCIM/Camera/IMG_1.jpg")

        id = jobs.move(listOf(pick), camera, busyPhotos, mapOf("IMG_1.jpg" to Clash.Replace))
        runCurrent()
        refusing = false
        jobs.resume(id)
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
        assertFalse(phone.exists("/DCIM/Camera/IMG_1.jpg"))
    }

    @Test
    fun `a folder gone before its move began fails, though one with its name is there`() = runTest {
        nas.folderAt("/Photos/Trip")
        val trip = entry(phone, "/DCIM/Camera/Trip")
        phone.delete("/DCIM/Camera/Trip/IMG_3.jpg")
        phone.delete("/DCIM/Camera/Trip")
        val jobs = jobs()

        val id = jobs.move(listOf(trip), camera, photos, mapOf("Trip" to Clash.Replace))
        advanceUntilIdle()

        assertEquals(Failure.Reason.NotFound, jobs.job(id).failures.single().reason)
    }

    @Test
    fun `a moved folder the server can't yet say is gone doesn't fail the rest`() = runTest {
        // As SMB answers while someone else still has the folder open: it goes once they let go.
        val pending =
            object : FileSystem by phone {
                override suspend fun stat(path: String): Entry? {
                    if (path == "/DCIM/Camera/Trip" && !phone.exists(path)) {
                        throw FileSystemException.Unreadable(path)
                    }
                    return phone.stat(path)
                }
            }
        drawers.value += Drawer("pending", "Pending", Drawer.Kind.Smb, "/", pending)
        val jobs = jobs()
        val from = Where("pending", "/DCIM/Camera", "Pending", "Pending › DCIM › Camera")
        val picks = listOf("Trip", "IMG_1.jpg").map { entry(phone, "/DCIM/Camera/$it") }

        val id = jobs.move(picks, from, photos, emptyMap())
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
    }

    @Test
    fun `a moved original changed while the job was paused stays`() = runTest {
        var id = 0L
        var edits = 0L
        var pausing = true
        val jobs = jobs()
        // Says when it last changed, and the job is paused as the original is about to go.
        val edited =
            object : FileSystem by phone {
                override suspend fun stat(path: String) = phone.stat(path)?.copy(modified = edits)

                override suspend fun delete(path: String) {
                    if (pausing) {
                        pausing = false
                        jobs.pause(id)
                        yield()
                    }
                    phone.delete(path)
                }
            }
        drawers.value += Drawer("edited", "Edited", Drawer.Kind.Phone, "/", edited)
        val from = Where("edited", "/DCIM/Camera", "Edited", "Edited › DCIM › Camera")

        id = jobs.move(listOf(entry(phone, "/DCIM/Camera/IMG_1.jpg")), from, photos, emptyMap())
        runCurrent()
        // Edited while the job was paused, to the same size.
        phone.file("/DCIM/Camera/IMG_1.jpg", "ONE")
        edits++
        jobs.resume(id)
        advanceUntilIdle()

        assertEquals(Failure.Reason.Mismatch, jobs.job(id).failures.single().reason)
        assertEquals("ONE", phone.text("/DCIM/Camera/IMG_1.jpg"))
        assertEquals("one", nas.text("/Photos/IMG_1.jpg"))
    }

    // What the seventh review found

    @Test
    fun `an empty moved folder whose delete went through as the job paused ends well`() = runTest {
        phone.folderAt("/DCIM/Camera/Empty")
        phone.lostAnswers += "/DCIM/Camera/Empty"
        val jobs = jobs()

        val id = jobs.move(listOf(entry(phone, "/DCIM/Camera/Empty")), camera, photos, emptyMap())
        runCurrent()
        jobs.pause(id)
        runCurrent()
        jobs.resume(id)
        advanceUntilIdle()

        assertTrue(jobs.job(id).failures.isEmpty())
        assertTrue(nas.exists("/Photos/Empty"))
        assertFalse(phone.exists("/DCIM/Camera/Empty"))
    }
}
