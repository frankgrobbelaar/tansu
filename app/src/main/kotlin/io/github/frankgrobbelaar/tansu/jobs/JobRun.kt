package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.isWithin
import io.github.frankgrobbelaar.tansu.files.join
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A drawer's file system, and whether it's across a network that can drop. */
internal class Side(val files: FileSystem, val remote: Boolean, val name: String)

/** The server named [name] stopped answering for longer than a job waits. */
internal class ConnectionLost(val name: String) : Exception("Lost $name")

/**
 * Carries out one job, step by step, handing each new state of it to [report]. Cancelling the
 * coroutine stops it between chunks; [inFlight] then names the file half written, if any.
 */
internal class JobRun(
    job: Job,
    private val source: Side,
    private val target: Side?,
    private val report: (Job) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    var job: Job = job
        private set

    /** The target file being written right now. */
    var inFlight: String? = null
        private set

    private var lastReport = 0L
    private var speedFrom = 0L to 0L

    suspend fun run(): Job {
        // A resumed or retried job counts again from nothing; what's done is quickly passed over.
        job = job.copy(status = Job.Status.Running, failures = emptyList(), progress = Progress())
        report(job)
        when (job.kind) {
            Job.Kind.Delete -> delete()
            Job.Kind.Copy,
            Job.Kind.Move -> transfer()
        }
        job = job.copy(status = Job.Status.Done, finishedAt = clock())
        report(job)
        return job
    }

    // Copying and moving

    private sealed interface Step

    private class MakeFolder(val path: String) : Step

    private class CopyFile(val from: Entry, val path: String) : Step

    private class Rename(val from: Entry, val path: String) : Step

    private suspend fun transfer() {
        val to = checkNotNull(job.to)
        val target = checkNotNull(target)
        val sameDrawer = job.from.drawerId == to.drawerId
        val taken = retrying { target.files.list(to.folder) }.map { it.name }.toMutableSet()
        val walked = mutableListOf<Entry>()
        val steps = job.picks.flatMap { pick -> plan(pick, to.folder, taken, sameDrawer, walked) }
        val files = steps.filterIsInstance<CopyFile>()
        val renames = steps.filterIsInstance<Rename>()
        progress {
            it.copy(
                bytesTotal =
                    files.sumOf { file -> file.from.size } + renames.sumOf { r -> r.from.size },
                itemsTotal = files.size + renames.size,
                counting = false,
            )
        }
        steps.forEach { step -> perform(step, target) }
        // A moved folder's files are gone; the folders go last, deepest first, if they're empty.
        if (job.kind == Job.Kind.Move) {
            walked
                .sortedByDescending { it.path.length }
                .forEach { folder ->
                    tryOrNull { source.files.delete(folder.path) }
                }
        }
    }

    /** The steps for one pick: none if it can't go there or is skipped. */
    private suspend fun plan(
        pick: Entry,
        folder: String,
        taken: MutableSet<String>,
        sameDrawer: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> {
        val placed = job.placed[pick.path]
        return when {
            sameDrawer && pick.isFolder && folder.isWithin(pick.path) -> {
                fail(pick, Failure.Reason.IntoItself)
                emptyList()
            }
            // Resumed or retried: back to the same place, merging into what's already there.
            placed != null -> stepsFor(pick, placed, sameDrawer, merge = true, walked)
            else -> settle(pick, folder, taken, sameDrawer, walked)
        }
    }

    /** Settles [pick]'s clash, if its name is taken, and remembers where it goes. */
    private suspend fun settle(
        pick: Entry,
        folder: String,
        taken: MutableSet<String>,
        sameDrawer: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> {
        val choice = choiceFor(pick, folder, taken, sameDrawer)
        if (choice == Clash.Skip) {
            job = job.copy(skipped = job.skipped + 1)
            return emptyList()
        }
        val name =
            if (choice == Clash.KeepBoth) {
                job = job.copy(keptBoth = job.keptBoth + 1)
                freeName(pick.name, pick.isFolder, taken)
            } else {
                pick.name
            }
        taken += name
        val path = join(folder, name)
        job = job.copy(placed = job.placed + (pick.path to path))
        return stepsFor(pick, path, sameDrawer, merge = choice == Clash.Replace, walked)
    }

    /**
     * Nothing if [pick]'s name is free; otherwise the user's choice, Keep both if they made none.
     */
    private fun choiceFor(
        pick: Entry,
        folder: String,
        taken: Set<String>,
        sameDrawer: Boolean,
    ): Clash? {
        if (pick.name !in taken) return null
        val chosen = job.clashes[pick.name] ?: Clash.KeepBoth
        // Replacing a file with itself is copying it onto itself: keep both instead.
        val ontoItself = sameDrawer && join(folder, pick.name) == pick.path
        return if (chosen == Clash.Replace && ontoItself) Clash.KeepBoth else chosen
    }

    /**
     * The steps that put [pick] at [path]. A move within one drawer is a rename, unless it has to
     * [merge] into a folder that's there.
     */
    private suspend fun stepsFor(
        pick: Entry,
        path: String,
        sameDrawer: Boolean,
        merge: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> =
        when {
            pick.path in job.done -> emptyList()
            job.kind == Job.Kind.Move && sameDrawer && !merge -> listOf(Rename(pick, path))
            pick.isFolder -> walk(pick, path, walked)
            else -> listOf(CopyFile(pick, path))
        }

    /** The folder [folder] and everything in it, as steps that recreate it at [path]. */
    private suspend fun walk(folder: Entry, path: String, walked: MutableList<Entry>): List<Step> {
        walked += folder
        val steps = mutableListOf<Step>(MakeFolder(path))
        val children = retrying { source.files.list(folder.path) }
        for (child in children) {
            val childPath = join(path, child.name)
            if (child.isFolder) steps += walk(child, childPath, walked)
            else steps += CopyFile(child, childPath)
        }
        return steps
    }

    private suspend fun perform(step: Step, target: Side) {
        when (step) {
            is MakeFolder ->
                try {
                    retrying { target.files.createFolder(step.path) }
                } catch (_: FileSystemException.Exists) {
                    // Merging into a folder that's already there.
                }
            is CopyFile -> attempt(step.from) { copy(step.from, step.path, target) }
            is Rename ->
                attempt(step.from) {
                    retrying { source.files.rename(step.from.path, step.path) }
                    finished(step.from, bytes = step.from.size)
                }
        }
    }

    private suspend fun copy(from: Entry, path: String, target: Side) {
        if (from.path in job.done) {
            progress { it.copy(bytesDone = it.bytesDone + from.size, itemsDone = it.itemsDone + 1) }
            return
        }
        val before = job.progress.bytesDone
        retrying {
            // A retry starts the file again.
            progress { it.copy(bytesDone = before, current = from.name) }
            stream(from, path, target)
        }
        if (job.kind == Job.Kind.Move) {
            val copied = retrying { target.files.stat(path) }
            if (copied?.size != from.size) {
                fail(from, Failure.Reason.Mismatch)
                return
            }
            retrying { source.files.delete(from.path) }
        }
        // Its bytes were counted as they streamed.
        finished(from, bytes = 0)
    }

    private suspend fun stream(from: Entry, path: String, target: Side) {
        source.files.read(from.path).use { input ->
            inFlight = path
            target.files.write(path).use { output ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    progress { it.copy(bytesDone = it.bytesDone + read) }
                }
            }
            inFlight = null
        }
    }

    // Deleting

    private suspend fun delete() {
        val order = mutableListOf<Entry>()
        for (pick in job.picks) order += if (pick.isFolder) inside(pick) else listOf(pick)
        progress {
            it.copy(
                bytesTotal = order.sumOf { entry -> entry.size },
                itemsTotal = order.size,
                counting = false,
            )
        }
        for (entry in order) {
            when {
                entry.path in job.done ->
                    progress {
                        it.copy(itemsDone = it.itemsDone + 1, bytesDone = it.bytesDone + entry.size)
                    }
                // A folder that kept something it couldn't delete fails on its own: say it once.
                entry.isFolder && job.failures.any { it.path.isWithin(entry.path) } -> Unit
                else -> {
                    progress { it.copy(current = entry.name) }
                    attempt(entry) {
                        retrying { source.files.delete(entry.path) }
                        finished(entry, bytes = entry.size)
                    }
                }
            }
        }
    }

    /** Everything in [folder], files before the folders that hold them. */
    private suspend fun inside(folder: Entry): List<Entry> {
        val all = mutableListOf<Entry>()
        for (child in retrying { source.files.list(folder.path) }) {
            all += if (child.isFolder) inside(child) else listOf(child)
        }
        return all + folder
    }

    // Shared

    /** Runs [block] for [entry]; a failure is noted against it and the job moves on. */
    private suspend fun attempt(entry: Entry, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: FileSystemException) {
            fail(entry, reasonOf(e))
        }
    }

    private fun finished(entry: Entry, bytes: Long) {
        job = job.copy(done = job.done + entry.path)
        progress { it.copy(itemsDone = it.itemsDone + 1, bytesDone = it.bytesDone + bytes) }
    }

    private fun fail(entry: Entry, reason: Failure.Reason) {
        job = job.copy(failures = job.failures + Failure(entry.name, entry.path, reason))
        progress { it.copy(itemsDone = it.itemsDone + 1) }
    }

    /**
     * Runs [block], trying again for a while when a server stops answering: Wi-Fi drops and
     * Tailscale reconnects. After that the job pauses, saying which server it lost.
     */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        val started = clock()
        while (true) {
            try {
                return block()
            } catch (e: FileSystemException.Unreachable) {
                giveUpAfter(started, e)
            } catch (e: IOException) {
                // A stream that broke part-way: the network, or the phone's storage.
                if (!source.remote && target?.remote != true) {
                    throw FileSystemException.Unreadable(job.progress.current ?: "", e)
                }
                giveUpAfter(started, e)
            }
            wait(RETRY_EVERY_MILLIS)
        }
    }

    private fun giveUpAfter(started: Long, cause: Exception) {
        if (clock() - started >= RETRY_FOR_MILLIS) {
            val lost = target?.takeIf { it.remote } ?: source
            throw ConnectionLost(lost.name).apply { initCause(cause) }
        }
    }

    private fun progress(change: (Progress) -> Progress) {
        val now = clock()
        var next = change(job.progress)
        val (since, bytesThen) = speedFrom
        if (now - since >= SPEED_WINDOW_MILLIS) {
            val speed = (next.bytesDone - bytesThen) * MILLIS_PER_SECOND / (now - since)
            next = next.copy(bytesPerSecond = speed.coerceAtLeast(0))
            speedFrom = now to next.bytesDone
        }
        job = job.copy(progress = next)
        if (now - lastReport >= REPORT_EVERY_MILLIS || next.itemsDone == next.itemsTotal) {
            lastReport = now
            report(job)
        }
    }

    private inline fun tryOrNull(block: () -> Unit) {
        try {
            block()
        } catch (_: FileSystemException) {
            // Best effort: a folder that kept something is still there to see.
        }
    }

    companion object {
        const val CHUNK = 256 * 1024
        const val RETRY_FOR_MILLIS = 30_000L
        const val RETRY_EVERY_MILLIS = 3_000L
        const val REPORT_EVERY_MILLIS = 150L
        const val SPEED_WINDOW_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/** [name] with the first free " (n)" before its extension, such as "IMG_2041 (1).jpg". */
internal fun freeName(name: String, isFolder: Boolean, taken: Set<String>): String {
    val dot = if (isFolder) -1 else name.lastIndexOf('.')
    val (base, extension) = if (dot > 0) name.take(dot) to name.drop(dot) else name to ""
    return generateSequence(1) { it + 1 }.map { "$base ($it)$extension" }.first { it !in taken }
}

/** Every failure a file system can report, as the reason a file didn't make it. */
internal fun reasonOf(e: FileSystemException): Failure.Reason =
    when (e) {
        is FileSystemException.NotFound -> Failure.Reason.NotFound
        is FileSystemException.AccessDenied -> Failure.Reason.AccessDenied
        is FileSystemException.NoSpace -> Failure.Reason.NoSpace
        is FileSystemException.Exists -> Failure.Reason.Exists
        is FileSystemException.Unreadable -> Failure.Reason.Unreadable
        is FileSystemException.Unreachable,
        is FileSystemException.AuthFailed,
        is FileSystemException.BadKey,
        is FileSystemException.ShareMissing,
        is FileSystemException.HostKeyUnknown,
        is FileSystemException.HostKeyChanged -> Failure.Reason.Unreachable
    }
