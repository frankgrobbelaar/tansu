package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.StreamRefused
import io.github.frankgrobbelaar.tansu.files.isWithin
import io.github.frankgrobbelaar.tansu.files.join
import io.github.frankgrobbelaar.tansu.files.nameOf
import io.github.frankgrobbelaar.tansu.files.parentOf
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
 * coroutine stops it between chunks; the job's [Job.partial] then names the file half written.
 *
 * Whatever goes wrong with one file or folder is put against it and the job goes on. Nothing is
 * ever written over unless the user chose Replace, and then only once the new copy is whole.
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

    private var lastReport = 0L
    private var speedFrom = 0L to 0L

    suspend fun run(): Job {
        // A resumed or retried job counts again from nothing; what's done is quickly passed over.
        job =
            job.copy(
                status = Job.Status.Running,
                failures = emptyList(),
                progress = Progress(),
                skipped = 0,
            )
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

    private sealed class Step(val from: Entry, val path: String)

    private class MakeFolder(from: Entry, path: String) : Step(from, path)

    /** [from] copied to [path]; over what's there if [replace]; a move [keep]s the original. */
    private class CopyFile(from: Entry, path: String, val replace: Boolean, val keep: Boolean) :
        Step(from, path)

    private class Rename(from: Entry, path: String) : Step(from, path)

    private suspend fun transfer() {
        val to = checkNotNull(job.to)
        val target = checkNotNull(target)
        val sameDrawer = job.from.drawerId == to.drawerId
        val taken =
            try {
                retrying { target.files.list(to.folder) }.map { it.name.lowercase() }.toMutableSet()
            } catch (e: FileSystemException) {
                // The folder they were going to is gone or closed: none of them can go there.
                job.picks.filter { it.path !in job.done }.forEach { fail(it, reasonOf(e)) }
                return
            }
        val walked = mutableListOf<Entry>()
        val steps = job.picks.flatMap { pick -> plan(pick, to.folder, taken, sameDrawer, walked) }
        val moving = steps.filter { it is CopyFile || it is Rename }
        progress {
            it.copy(
                bytesTotal = moving.sumOf { step -> step.from.size },
                itemsTotal = moving.size,
                counting = false,
            )
        }
        val blocked = mutableMapOf<String, Failure.Reason>()
        steps.forEach { step -> perform(step, target, blocked) }
        // A moved folder's files are gone; the folders go last, deepest first, if they're empty.
        if (job.kind == Job.Kind.Move) {
            walked
                .sortedByDescending { it.path.length }
                .filter { folder -> job.failures.none { it.path.isWithin(folder.path) } }
                .forEach { folder -> tryOrNull { source.files.delete(folder.path) } }
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
            // Resumed or retried: back to where it was going, over what this job left there.
            placed != null -> stepsFor(pick, placed, sameDrawer, resumed = true, walked)
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
        taken += name.lowercase()
        val placement = Placement(join(folder, name), replace = choice == Clash.Replace)
        job = job.copy(placed = job.placed + (pick.path to placement))
        return stepsFor(pick, placement, sameDrawer, resumed = false, walked)
    }

    /**
     * Nothing if [pick]'s name is free; otherwise the user's choice, Keep both if they made none.
     * Names are told apart without case, as the phone and SMB do.
     */
    private fun choiceFor(
        pick: Entry,
        folder: String,
        taken: Set<String>,
        sameDrawer: Boolean,
    ): Clash? {
        if (pick.name.lowercase() !in taken) return null
        val chosen = job.clashes[pick.name] ?: Clash.KeepBoth
        // Replacing a file with itself is copying it onto itself: keep both instead.
        val ontoItself = sameDrawer && join(folder, pick.name) == pick.path
        return if (chosen == Clash.Replace && ontoItself) Clash.KeepBoth else chosen
    }

    /**
     * The steps that put [pick] where [placement] says. A move within one drawer is a rename,
     * unless it's to replace what's there. A job [resumed] writes over what it left there.
     */
    private suspend fun stepsFor(
        pick: Entry,
        placement: Placement,
        sameDrawer: Boolean,
        resumed: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> {
        val replace = placement.replace || resumed
        return when {
            pick.path in job.done -> emptyList()
            job.kind == Job.Kind.Move && sameDrawer && !placement.replace ->
                listOf(Rename(pick, placement.path))
            pick.isFolder -> {
                // A picked link is followed for what's in it, but a move takes only the link.
                if (pick.isLink && job.kind == Job.Kind.Move) walked += pick
                walk(pick, placement.path, replace, keep = pick.isLink, walked)
            }
            else -> listOf(CopyFile(pick, placement.path, replace, keep = false))
        }
    }

    /**
     * [folder] and everything in it, as steps that recreate it at [path]. Links inside it point
     * somewhere else entirely, maybe back up the tree, so they're left where they are. Files
     * reached through a link are [keep]t on a move.
     */
    private suspend fun walk(
        folder: Entry,
        path: String,
        replace: Boolean,
        keep: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> {
        val children =
            try {
                retrying { source.files.list(folder.path) }
            } catch (e: FileSystemException) {
                fail(folder, reasonOf(e))
                return emptyList()
            }
        if (!keep) walked += folder
        val steps = mutableListOf<Step>(MakeFolder(folder, path))
        for (child in children) {
            val childPath = job.placed[child.path]?.path ?: join(path, child.name)
            when {
                child.isLink -> fail(child, Failure.Reason.Link)
                child.isFolder -> steps += walk(child, childPath, replace, keep, walked)
                else -> steps += CopyFile(child, childPath, replace, keep)
            }
        }
        return steps
    }

    private suspend fun perform(
        step: Step,
        target: Side,
        blocked: MutableMap<String, Failure.Reason>,
    ) {
        // A folder that couldn't be made takes everything meant for it down with it.
        val stoppedBy = blocked.entries.firstOrNull { step.path.isWithin(it.key) }?.value
        if (stoppedBy != null) {
            if (step is MakeFolder) blocked[step.path] = stoppedBy else fail(step.from, stoppedBy)
            return
        }
        when (step) {
            is MakeFolder ->
                try {
                    makeFolder(step.path, target)
                } catch (e: FileSystemException) {
                    fail(step.from, reasonOf(e))
                    blocked[step.path] = reasonOf(e)
                }
            is CopyFile -> attempt(step.from) { copy(step, target) }
            is Rename ->
                attempt(step.from) {
                    retrying { source.files.rename(step.from.path, step.path) }
                    finished(step.from, bytes = step.from.size)
                }
        }
    }

    /** Makes the folder at [path], or merges into the folder that's there; a file there fails. */
    private suspend fun makeFolder(path: String, target: Side) {
        try {
            retrying { target.files.createFolder(path) }
        } catch (e: FileSystemException.Exists) {
            // Merging into a folder that's there, or one this job made before it stopped.
            if (retrying { target.files.stat(path) }?.isFolder != true) throw e
        }
    }

    private suspend fun copy(step: CopyFile, target: Side) {
        val from = step.from
        if (from.path in job.done) {
            progress { it.copy(bytesDone = it.bytesDone + from.size, itemsDone = it.itemsDone + 1) }
            return
        }
        val before = job.progress.bytesDone
        val path =
            try {
                retrying {
                    // A retry starts the file again.
                    progress { it.copy(bytesDone = before, current = from.name) }
                    place(from, step.path, step.replace, target)
                }
            } catch (e: FileSystemException) {
                // A file that failed part-way isn't left behind looking whole.
                job.partial?.let { partial -> tryOrNull { target.files.delete(partial) } }
                job = job.copy(partial = null)
                throw e
            }
        if (job.kind == Job.Kind.Move && !step.keep) {
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

    /** Puts [from] at [path], over what's there only if [replace]. Says where it went. */
    private suspend fun place(from: Entry, path: String, replace: Boolean, target: Side): String {
        // What this job half wrote there before is its own to write over.
        val ours = job.partial == path
        val there = if (replace && !ours) target.files.stat(path) else null
        // A file can't take a folder's place.
        if (there?.isFolder == true) throw FileSystemException.Exists(path)
        if (there != null) {
            // The new copy goes beside the original and takes its place only once it's whole,
            // so stopping part-way never costs the original.
            val whole = join(parentOf(path), ".${nameOf(path)}.tansu")
            stream(from, whole, replace = true, target)
            try {
                target.files.delete(path)
            } catch (_: FileSystemException.NotFound) {
                // Gone already: the new copy only has to take its name.
            }
            target.files.rename(whole, path)
            return path
        }
        return try {
            stream(from, path, replace = replace || ours, target)
            path
        } catch (_: FileSystemException.Exists) {
            beside(from, path, target)
        }
    }

    /**
     * Keeps both when [path] turns out to be taken after all, say by a name that differs only in
     * case: [from] goes under the first free name, which a resumed job remembers.
     */
    private suspend fun beside(from: Entry, path: String, target: Side): String {
        val folder = parentOf(path)
        val taken = target.files.list(folder).map { it.name.lowercase() }.toSet()
        val free = join(folder, freeName(nameOf(path), isFolder = false, taken))
        job =
            job.copy(
                placed = job.placed + (from.path to Placement(free, replace = false)),
                keptBoth = job.keptBoth + 1,
            )
        stream(from, free, replace = false, target)
        return free
    }

    private suspend fun stream(from: Entry, path: String, replace: Boolean, target: Side) {
        source.files.read(from.path).use { input ->
            target.files.write(path, replace).use { output ->
                // Only once it's open is the file this job's to remove if it's cancelled.
                job = job.copy(partial = path)
                val buffer = ByteArray(CHUNK)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    progress { it.copy(bytesDone = it.bytesDone + read) }
                }
            }
        }
        job = job.copy(partial = null)
    }

    // Deleting

    private suspend fun delete() {
        val order = mutableListOf<Entry>()
        for (pick in job.picks) {
            // A link goes on its own: what it points to isn't the user's to lose here.
            order +=
                if (pick.isFolder && !pick.isLink && pick.path !in job.done) inside(pick)
                else listOf(pick)
        }
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
                        try {
                            retrying { source.files.delete(entry.path) }
                        } catch (_: FileSystemException.NotFound) {
                            // Already gone, which is what deleting it was for.
                        }
                        finished(entry, bytes = entry.size)
                    }
                }
            }
        }
    }

    /** Everything in [folder], files before the folders that hold them. Links aren't gone into. */
    private suspend fun inside(folder: Entry): List<Entry> {
        val children =
            try {
                retrying { source.files.list(folder.path) }
            } catch (_: FileSystemException.NotFound) {
                // Gone already, with all it held.
                return listOf(folder)
            } catch (e: FileSystemException) {
                fail(folder, reasonOf(e))
                return emptyList()
            }
        val all = mutableListOf<Entry>()
        for (child in children) {
            all += if (child.isFolder && !child.isLink) inside(child) else listOf(child)
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
     * Tailscale reconnects. After that the job pauses, saying which server it lost. A full disk or
     * a refusal isn't waited out.
     */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        val started = clock()
        while (true) {
            try {
                return block()
            } catch (e: FileSystemException.Unreachable) {
                giveUpAfter(started, e)
            } catch (e: StreamRefused) {
                throw e.refusal
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

/**
 * [name] with the first free " (n)" before its extension, such as "IMG_2041 (1).jpg". [taken] holds
 * the names in the folder, in lower case.
 */
internal fun freeName(name: String, isFolder: Boolean, taken: Set<String>): String {
    val dot = if (isFolder) -1 else name.lastIndexOf('.')
    val (base, extension) = if (dot > 0) name.take(dot) to name.drop(dot) else name to ""
    return generateSequence(1) { it + 1 }
        .map { "$base ($it)$extension" }
        .first { it.lowercase() !in taken }
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
