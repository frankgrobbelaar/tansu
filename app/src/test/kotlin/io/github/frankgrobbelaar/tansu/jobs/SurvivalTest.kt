package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.MemoryFileSystem
import io.github.frankgrobbelaar.tansu.files.nameOf
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A job survives Android ending tansu's process at any step: brought back from its journal and
 * resumed, it ends just as one that ran straight through, with nothing lost, doubled or left
 * half-written. The process ends just before each change to a drawer, and just after it with its
 * answer lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurvivalTest {
    @get:Rule val folder = TemporaryFolder()

    /** The phone, a server and a case-blind share, with a few things on each. */
    private class World {
        val phone =
            MemoryFileSystem()
                .file("/DCIM/Camera/IMG_1.jpg", "one")
                .file("/DCIM/Camera/IMG_2.jpg", "two!")
                .file("/DCIM/Camera/Trip/IMG_3.jpg", "three")
                .file("/DCIM/Camera/Trip/Day 2/IMG_4.jpg", "four")
                .folderAt("/Pictures")
        val nas = MemoryFileSystem().folderAt("/Photos")
        val share = MemoryFileSystem(ignoreCase = true).folderAt("/Media")

        /** Every path and what each file holds. */
        fun state() =
            listOf(phone, nas, share).map { files ->
                files.paths().associateWith { files.text(it) }
            }
    }

    private val camera = Where("phone", "/DCIM/Camera", "Phone", "Phone › DCIM › Camera")
    private val pictures = Where("phone", "/Pictures", "Phone", "Phone › Pictures")
    private val photos = Where("nas", "/Photos", "NAS", "NAS › Photos")
    private val media = Where("share", "/Media", "Share", "Share › Media")

    private fun picks(world: World, vararg names: String): List<Entry> = runBlocking {
        names.map { checkNotNull(world.phone.stat("/DCIM/Camera/$it")) }
    }

    @Test
    fun `a copy into folders survives at every step`() = survives { world, jobs ->
        jobs.copy(picks(world, "IMG_1.jpg", "IMG_2.jpg", "Trip"), camera, photos, emptyMap())
    }

    @Test
    fun `a move that replaces one file and keeps both of another survives at every step`() =
        survives(
            prepare = {
                it.nas.file("/Photos/IMG_1.jpg", "old one").file("/Photos/IMG_2.jpg", "old")
            }
        ) { world, jobs ->
            val clashes = mapOf("IMG_1.jpg" to Clash.Replace, "IMG_2.jpg" to Clash.KeepBoth)
            jobs.move(picks(world, "IMG_1.jpg", "IMG_2.jpg", "Trip"), camera, photos, clashes)
        }

    @Test
    fun `a move within one drawer survives at every step`() = survives { world, jobs ->
        jobs.move(picks(world, "IMG_1.jpg", "Trip"), camera, pictures, emptyMap())
    }

    @Test
    fun `a delete brought back is cancelled at every step, taking nothing more`() = runTest {
        // Brought back days later, it would list its folders afresh and take what's arrived since.
        val start: (World, Jobs) -> Unit = { world, jobs ->
            jobs.delete(picks(world, "IMG_2.jpg", "Trip"), camera)
        }
        val steps = stepsOf(World(), start)
        for (step in 1..steps) {
            for (after in listOf(false, true)) {
                val world = World()
                val journal = Freezing(FileJournal(folder.newFolder()))
                val death = Death(atStep = step, after = after).also { it.journal = journal }
                val dying = processOn(world, journal, death)
                start(world, dying.jobs)
                advanceUntilIdle()
                dying.scope.cancel()
                val left = world.state()

                val again = processOn(world, journal.thawed(), death = null)
                advanceUntilIdle()

                val back = again.jobs.all.value.single()
                assertEquals(
                    "Ended at step $step, after: $after",
                    Job.Status.Cancelled,
                    back.status,
                )
                assertEquals(left, world.state())
                assertTrue(again.journal.saved().isEmpty())
            }
        }
    }

    @Test
    fun `a moved file's copy changed on the server while tansu was closed is kept`() = runTest {
        // The copy has its name; tansu ends before the original goes. Someone edits the copy.
        val world = World()
        val journal = Freezing(FileJournal(folder.newFolder()))
        val death = Death(atStep = BEFORE_ORIGINAL_GOES).also { it.journal = journal }
        val dying = processOn(world, journal, death)
        dying.jobs.move(picks(world, "IMG_1.jpg"), camera, photos, emptyMap())
        advanceUntilIdle()
        dying.scope.cancel()
        world.nas.file("/Photos/IMG_1.jpg", "one, edited")

        val again = processOn(world, journal.thawed(), death = null)
        again.jobs.resume(again.jobs.all.value.single().id)
        advanceUntilIdle()

        assertEquals("one, edited", world.nas.text("/Photos/IMG_1.jpg"))
        assertEquals("one", world.phone.text("/DCIM/Camera/IMG_1.jpg"))
        val failure = again.jobs.all.value.single().failures.single()
        assertEquals(Failure.Reason.Mismatch, failure.reason)
    }

    @Test
    fun `a share-in brought back is cancelled, without its half`() = runTest {
        // The other app only lent its files while it shared them: they're gone with the process.
        val film = "0123456789abcdef".repeat(BIG / SIXTEEN)
        val lent = MemoryFileSystem().file("/film.mp4", film)
        val world = World()
        val journal = Freezing(FileJournal(folder.newFolder()))
        val death = Death(atStep = Int.MAX_VALUE, atByte = BREAK).also { it.journal = journal }
        val dying = processOn(world, journal, death)
        val from = Where("shared", "/", "Gallery", "Gallery")
        dying.jobs.copyIn(lent, listOf(checkNotNull(lent.stat("/film.mp4"))), from, photos)
        advanceUntilIdle()
        dying.scope.cancel()
        assertTrue(world.nas.paths().any { nameOf(it).startsWith(".tansu-") })

        val again = processOn(world, journal.thawed(), death = null)
        advanceUntilIdle()

        assertEquals(Job.Status.Cancelled, again.jobs.all.value.single().status)
        assertEquals(setOf("/Photos"), world.nas.paths())
        assertTrue(again.journal.saved().isEmpty())
    }

    @Test
    fun `two folders whose names differ only in case survive at every step`() =
        survives(prepare = { it.phone.file("/DCIM/Camera/TRIP/IMG_5.jpg", "five") }) { world, jobs
            ->
            jobs.copy(picks(world, "Trip", "TRIP"), camera, media, emptyMap())
        }

    @Test
    fun `a big file cut off by the process ending carries on from where it was`() = runTest {
        val film = "0123456789abcdef".repeat(BIG / SIXTEEN)
        val start: (World) -> Unit = { it.phone.file("/DCIM/Camera/film.mp4", film) }
        val expected =
            runStraight(start) { world, jobs ->
                jobs.copy(picks(world, "film.mp4"), camera, photos, emptyMap())
            }
        val world = World().also(start)
        val death = Death(atStep = Int.MAX_VALUE, atByte = BREAK)

        bringBack(world, death) { jobs ->
            jobs.copy(picks(world, "film.mp4"), camera, photos, emptyMap())
        }

        assertEquals(expected, world.state())
        // It carried on, less the last stretch, rather than starting again.
        assertEquals(listOf(BREAK - JobRun.WRITTEN_AGAIN), world.nas.writeOffsets)
    }

    /**
     * Runs [start] once straight through, then again for every step on a drawer, ending the process
     * just before that step and just after it, then bringing the job back and resuming it: each
     * time, every drawer must end as the straight run left it.
     */
    private fun survives(prepare: (World) -> Unit = {}, start: (World, Jobs) -> Unit) = runTest {
        val expected = runStraight(prepare, start)
        val steps = World().also(prepare).let { stepsOf(it, start) }
        assertTrue("A job with no steps tests nothing", steps > 0)
        for (step in 1..steps) {
            for (after in listOf(false, true)) {
                val world = World().also(prepare)
                bringBack(world, Death(atStep = step, after = after)) { jobs -> start(world, jobs) }
                assertEquals("Ended at step $step, after: $after", expected, world.state())
            }
        }
    }

    private fun TestScope.runStraight(prepare: (World) -> Unit, start: (World, Jobs) -> Unit): Any {
        val world = World().also(prepare)
        val jobs = jobsOn(world, Journal.None, death = null)
        start(world, jobs)
        advanceUntilIdle()
        assertTrue(jobs.all.value.all { it.status == Job.Status.Done && it.failures.isEmpty() })
        return world.state()
    }

    private fun TestScope.stepsOf(world: World, start: (World, Jobs) -> Unit): Int {
        val counting = Death(atStep = Int.MAX_VALUE)
        start(world, jobsOn(world, Journal.None, counting))
        advanceUntilIdle()
        return counting.steps
    }

    /**
     * Starts a job with [start] in a process that [death] ends, then brings it back in a new one
     * from what its journal kept, and resumes it to the end.
     */
    private fun TestScope.bringBack(world: World, death: Death, start: (Jobs) -> Unit) {
        val journal = Freezing(FileJournal(folder.newFolder()))
        death.journal = journal
        val dying = processOn(world, journal, death)
        start(dying.jobs)
        advanceUntilIdle()
        dying.scope.cancel()

        val again = processOn(world, journal.thawed(), death = null)
        val back = again.jobs.all.value.single()
        assertTrue(back.status is Job.Status.Paused)
        again.jobs.resume(back.id)
        advanceUntilIdle()
        val ended = again.jobs.all.value.single()
        assertEquals(Job.Status.Done, ended.status)
        assertTrue(ended.failures.isEmpty())
        assertTrue(again.journal.saved().isEmpty())
        assertTrue(dying.unexpected.isEmpty() && again.unexpected.isEmpty())
    }

    /** One process: its jobs, its scope, its journal, and anything but its end that escaped it. */
    private class Process(
        val jobs: Jobs,
        val scope: CoroutineScope,
        val journal: Journal,
        val unexpected: List<Throwable>,
    )

    private fun TestScope.jobsOn(world: World, journal: Journal, death: Death?): Jobs =
        processOn(world, journal, death).jobs

    private fun TestScope.processOn(world: World, journal: Journal, death: Death?): Process {
        val wrap = { files: FileSystem -> death?.let { Dying(files, it) } ?: files }
        val drawers =
            MutableStateFlow(
                listOf(
                    Drawer("phone", "Phone", Drawer.Kind.Phone, "/", wrap(world.phone)),
                    Drawer("nas", "NAS", Drawer.Kind.Sftp, "/", wrap(world.nas)),
                    Drawer("share", "Share", Drawer.Kind.Smb, "/", wrap(world.share)),
                )
            )
        // The process's end escapes the engine, as nothing else may: anything else is kept.
        val unexpected = mutableListOf<Throwable>()
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    StandardTestDispatcher(testScheduler) +
                    CoroutineExceptionHandler { _, e -> if (e !is Ended) unexpected += e }
            )
        val jobs =
            Jobs(
                drawers = drawers,
                scope = scope,
                io = StandardTestDispatcher(testScheduler),
                clock = { testScheduler.currentTime },
                journal = journal,
            )
        return Process(jobs, scope, journal, unexpected)
    }

    /** The process ending: nothing after it happens, and nothing more is written down. */
    private class Ended : RuntimeException()

    /** Ends the process at its [atStep]th change to a drawer, or [atByte] bytes into a file. */
    private class Death(
        val atStep: Int,
        val after: Boolean = false,
        val atByte: Long = Long.MAX_VALUE,
    ) {
        var steps = 0
        var bytes = 0L
        var journal: Freezing? = null

        suspend fun <T> step(change: suspend () -> T): T {
            if (++steps != atStep) return change()
            if (after) change()
            die()
        }

        fun wrote(count: Int) {
            bytes += count
            if (bytes >= atByte) die()
        }

        fun die(): Nothing {
            journal?.frozen = true
            throw Ended()
        }
    }

    /** [files], with each change a step [death] may end the process at. */
    private class Dying(private val files: FileSystem, private val death: Death) :
        FileSystem by files {
        override suspend fun write(path: String, replace: Boolean): OutputStream =
            counted(death.step { files.write(path, replace) })

        override suspend fun writeAt(path: String, offset: Long): OutputStream =
            counted(death.step { files.writeAt(path, offset) })

        override suspend fun createFolder(path: String) = death.step { files.createFolder(path) }

        override suspend fun rename(from: String, to: String, replace: Boolean) = death.step {
            files.rename(from, to, replace)
        }

        override suspend fun delete(path: String) = death.step { files.delete(path) }

        private fun counted(out: OutputStream) =
            object : FilterOutputStream(out) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    death.wrote(len)
                }
            }
    }

    /** A journal that stops writing once the process has ended, then reads back what it kept. */
    private class Freezing(private val journal: Journal) : Journal {
        @Volatile var frozen = false

        fun thawed(): Journal = journal

        override fun saved() = journal.saved()

        override fun begin(job: Job) {
            if (!frozen) journal.begin(job)
        }

        override fun note(id: Long, changes: List<Change>) {
            if (!frozen) journal.note(id, changes)
        }

        override fun end(id: Long) {
            if (!frozen) journal.end(id)
        }
    }

    private companion object {
        /**
         * A move of one file: its hidden file is written (1), named (2), then the original goes.
         */
        const val BEFORE_ORIGINAL_GOES = 3

        const val SIXTEEN = 16
        const val BIG = 3 * 1024 * 1024
        const val BREAK = 5 * 512 * 1024L
    }
}
