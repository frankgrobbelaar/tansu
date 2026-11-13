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
 * One file that can't be done, for [reason]: found by the job itself, or said by where the files
 * come from ([atSource]) rather than where they go.
 */
private class FileFailed(val reason: Failure.Reason, val atSource: Boolean = false) : Exception()

/**
 * Carries out one job, step by step, handing each new state of it to [report].
 *
 * Every file is written under a hidden name of this job's own, and only takes its real name once
 * it's whole: it never writes over anything unless the user chose Replace, and a stop part-way
 * leaves nothing but that hidden file, which the job's [Job.partial] names for cancelling. Whatever
 * goes wrong with one file or folder is put against it and the job goes on.
 */
internal class JobRun(
    job: Job,
    private val source: Side,
    private val target: Side?,
    private val report: (Job) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    /** All of the job but the sets that grow with every file, which are kept apart below. */
    private var state: Job = job
    private val done = job.done.toHashSet()
    private val written = job.written.toHashSet()

    /** The job as it stands, for reports and for whoever stops it. */
    val job: Job
        get() = state.copy(done = done.toSet(), written = written.toSet())

    private var lastReport = 0L
    private var speedFrom = 0L to 0L

    suspend fun run(): Job {
        // A resumed or retried job counts again from nothing; what's done is quickly passed over.
        state =
            state.copy(
                status = Job.Status.Running,
                failures = emptyList(),
                progress = Progress(),
                skipped = 0,
            )
        report(job)
        when (state.kind) {
            Job.Kind.Delete -> delete()
            Job.Kind.Copy,
            Job.Kind.Move -> transfer()
        }
        state = state.copy(status = Job.Status.Done, finishedAt = clock())
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

    /** The target folder's names, and which of them this job put there, all in lower case. */
    private class Names(val there: MutableSet<String>, val ours: MutableSet<String>)

    private suspend fun transfer() {
        val to = checkNotNull(state.to)
        val target = checkNotNull(target)
        val sameDrawer = state.from.drawerId == to.drawerId
        val names =
            try {
                val there = retrying { target.files.list(to.folder) }.map { it.name.lowercase() }
                // What this job placed before it stopped is its own, not a clash.
                val ours =
                    state.placed.values
                        .filter { parentOf(it.path) == to.folder }
                        .map { nameOf(it.path).lowercase() }
                Names(there.toMutableSet(), ours.toMutableSet())
            } catch (e: FileSystemException) {
                // The folder they were going to is gone or closed: none of them can go there.
                state.picks.filter { it.path !in done }.forEach { fail(it, reasonOf(e)) }
                return
            }
        val walked = mutableListOf<Entry>()
        val plans = state.picks.map { it to plan(it, to.folder, names, sameDrawer, walked) }
        val moving = plans.flatMap { (_, steps) -> steps }.filter { it is CopyFile || it is Rename }
        progress {
            it.copy(
                bytesTotal = moving.sumOf { step -> step.from.size },
                itemsTotal = moving.size,
                counting = false,
            )
        }
        val blocked = mutableMapOf<String, Failure.Reason>()
        for ((pick, steps) in plans) {
            steps.forEach { step -> perform(step, target, blocked) }
            if (pick.isFolder && steps.isNotEmpty()) finishFolder(pick, walked)
        }
    }

    /**
     * A folder whose steps are through: if it was moved, its folders go, deepest first, if they're
     * empty; and with nothing failed in it, it's done, so a resumed job passes it over.
     */
    private suspend fun finishFolder(folder: Entry, walked: List<Entry>) {
        val failedIn = { path: String -> state.failures.any { it.path.isWithin(path) } }
        if (state.kind == Job.Kind.Move) {
            walked
                .filter { it.path.isWithin(folder.path) && !failedIn(it.path) }
                .sortedByDescending { it.path.length }
                .forEach { tryOrNull { source.files.delete(it.path) } }
        }
        if (!failedIn(folder.path)) done += folder.path
    }

    /** The steps for one pick: none if it can't go there or is skipped. */
    private suspend fun plan(
        pick: Entry,
        folder: String,
        names: Names,
        sameDrawer: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> {
        val placed = state.placed[pick.path]
        return when {
            sameDrawer && pick.isFolder && folder.isWithin(pick.path) -> {
                fail(pick, Failure.Reason.IntoItself)
                emptyList()
            }
            // Resumed or retried: back to where it was going, without asking again.
            placed != null -> stepsFor(pick, placed, sameDrawer, walked)
            else -> settle(pick, folder, names, sameDrawer, walked)
        }
    }

    /** Settles [pick]'s clash, if its name is taken, and remembers where it goes. */
    private suspend fun settle(
        pick: Entry,
        folder: String,
        names: Names,
        sameDrawer: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> {
        val choice = choiceFor(pick, folder, names, sameDrawer)
        if (choice == Clash.Skip) {
            state = state.copy(skipped = state.skipped + 1)
            return emptyList()
        }
        val name =
            if (choice == Clash.KeepBoth) {
                state = state.copy(keptBoth = state.keptBoth + 1)
                freeName(pick.name, pick.isFolder, names.there + names.ours)
            } else {
                pick.name
            }
        names.ours += name.lowercase()
        val placement = Placement(join(folder, name), replace = choice == Clash.Replace)
        state = state.copy(placed = state.placed + (pick.path to placement))
        return stepsFor(pick, placement, sameDrawer, walked)
    }

    /**
     * Nothing if [pick]'s name is free; otherwise the user's choice, Keep both if they made none.
     * Names are told apart without case, as the phone and SMB do. A name another pick of this job
     * already took is never replaced: both are the user's.
     */
    private fun choiceFor(pick: Entry, folder: String, names: Names, sameDrawer: Boolean): Clash? {
        val name = pick.name.lowercase()
        if (name in names.ours) return Clash.KeepBoth
        if (name !in names.there) return null
        val chosen = state.clashes[pick.name] ?: Clash.KeepBoth
        // Replacing a file with itself is copying it onto itself: keep both instead.
        val ontoItself = sameDrawer && join(folder, pick.name) == pick.path
        return if (chosen == Clash.Replace && ontoItself) Clash.KeepBoth else chosen
    }

    /**
     * The steps that put [pick] where [placement] says. A move within one drawer is a rename,
     * unless it's to replace what's there.
     */
    private suspend fun stepsFor(
        pick: Entry,
        placement: Placement,
        sameDrawer: Boolean,
        walked: MutableList<Entry>,
    ): List<Step> =
        when {
            pick.path in done -> emptyList()
            state.kind == Job.Kind.Move && sameDrawer && !placement.replace ->
                listOf(Rename(pick, placement.path))
            pick.isFolder -> {
                // A picked link is followed for what's in it, but a move takes only the link.
                if (pick.isLink && state.kind == Job.Kind.Move) walked += pick
                walk(pick, placement.path, placement.replace, keep = pick.isLink, walked)
            }
            else -> listOf(CopyFile(pick, placement.path, placement.replace, keep = false))
        }

    /**
     * [folder] and everything in it, as steps that recreate it at [path], merging into what's there
     * if [replace]. Links inside it point somewhere else entirely, maybe back up the tree, so
     * they're left where they are. Files reached through a link are [keep]t on a move.
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
                fail(folder, reasonOf(e), atSource = true)
                return emptyList()
            }
        if (!keep) walked += folder
        val steps = mutableListOf<Step>(MakeFolder(folder, path))
        // Two names that differ only in case are one name on the phone and SMB: a second folder
        // goes beside the first rather than into it. A second file is kept beside when named.
        val names = children.mapTo(mutableSetOf()) { it.name.lowercase() }
        val twins = children.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }
        val seen = mutableSetOf<String>()
        for (child in children) {
            val first = seen.add(child.name.lowercase())
            val own = join(path, child.name)
            val childPath =
                state.placed[child.path]?.path
                    ?: when {
                        !child.isFolder || child.name.lowercase() !in twins -> own
                        first -> remember(child, own)
                        else -> remember(child, apart(child.name, path, names))
                    }
            // Only what keeps its own name merges into or replaces what's there.
            val replaces = replace && childPath == own
            when {
                child.isLink -> fail(child, Failure.Reason.Link)
                child.isFolder -> steps += walk(child, childPath, replaces, keep, walked)
                else -> steps += CopyFile(child, childPath, replaces, keep)
            }
        }
        return steps
    }

    /** A free place in [path] for a folder whose name another beside it has but for case. */
    private fun apart(name: String, path: String, names: MutableSet<String>): String {
        val free = freeName(name, isFolder = true, names)
        names += free.lowercase()
        return join(path, free)
    }

    /** Remembers where [folder] goes, so a resumed job that lists its twin first agrees. */
    private fun remember(folder: Entry, path: String): String {
        state =
            state.copy(placed = state.placed + (folder.path to Placement(path, replace = false)))
        return path
    }

    private suspend fun perform(
        step: Step,
        target: Side,
        blocked: MutableMap<String, Failure.Reason>,
    ) {
        if (step is CopyFile && step.from.path in done) {
            // Done before the job stopped: quickly passed over.
            val size = step.from.size
            progress { it.copy(bytesDone = it.bytesDone + size, itemsDone = it.itemsDone + 1) }
            return
        }
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
                    try {
                        retrying { source.files.rename(step.from.path, step.path) }
                    } catch (e: FileSystemException.NotFound) {
                        // Its answer was lost the first time: done if it's there now.
                        if (retrying { source.files.stat(step.path) } == null) throw e
                    }
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
        val path =
            state.copied[from.path]
                ?: try {
                    val before = state.progress.bytesDone
                    retrying {
                        // A retry starts the file again.
                        progress {
                            it.copy(
                                bytesDone = before,
                                current = from.name,
                                currentPath = from.path,
                            )
                        }
                        place(step, target)
                    }
                } catch (e: FileSystemException) {
                    throw e.also { dropPartial(target) }
                } catch (e: FileFailed) {
                    throw e.also { dropPartial(target) }
                }
        if (state.kind == Job.Kind.Move && !step.keep) finishMove(from, path, target)
        // Its bytes were counted as they streamed.
        finished(from, bytes = 0)
    }

    /**
     * Removes a moved file's original once its copy at [path] checks out. Remembered first, so a
     * job that stops here checks that copy rather than making another.
     */
    private suspend fun finishMove(from: Entry, path: String, target: Side) {
        state = state.copy(copied = state.copied + (from.path to path))
        // Gone already: its delete went through, though the answer didn't.
        val original = atSource { retrying { source.files.stat(from.path) } } ?: return
        if (retrying { target.files.stat(path) }?.size != original.size) {
            // A copy that doesn't match is no use: it goes, the original stays, and a retry
            // copies it afresh.
            tryOrNull { target.files.delete(path) }
            written -= path.lowercase()
            state = state.copy(copied = state.copied - from.path)
            throw FileFailed(Failure.Reason.Mismatch)
        }
        try {
            atSource { retrying { source.files.delete(from.path) } }
        } catch (e: FileFailed) {
            if (e.reason != Failure.Reason.NotFound) throw e
        }
    }

    /** What the job half wrote goes with a file that failed. */
    private suspend fun dropPartial(target: Side) {
        state.partial?.let { partial -> tryOrNull { target.files.delete(partial) } }
        state = state.copy(partial = null, landing = null)
    }

    /** Runs [block] against where the files come from; a refusal there is said as such. */
    private suspend fun <T> atSource(block: suspend () -> T): T =
        try {
            block()
        } catch (e: FileSystemException) {
            // A server that stops answering is waited out the same on either side.
            if (e is FileSystemException.Unreachable) throw e
            throw FileFailed(reasonOf(e), atSource = true)
        }

    /**
     * This job's own hidden name for [step]'s file, beside where it goes: unique to the job, the
     * same wherever the file ends up being named, and short, so a long name still fits beside it.
     */
    private fun hiddenFor(step: CopyFile): String {
        val hash = Integer.toUnsignedString(step.from.path.hashCode(), RADIX)
        return join(parentOf(step.path), ".tansu-${state.token}-$hash")
    }

    /**
     * Writes [step]'s file under this job's hidden name beside where it goes, checks it's all
     * there, then gives it its name. Says where it went.
     */
    private suspend fun place(step: CopyFile, target: Side): String {
        val hidden = hiddenFor(step)
        val landing = state.landing
        if (state.partial == hidden && landing != null) {
            // It stopped as it was being named: if the hidden file's gone, the naming happened.
            return if (target.files.stat(hidden) == null) landed(landing)
            else name(step, hidden, target)
        }
        // Known before it's opened, so a stop while it opens still knows what to remove.
        state = state.copy(partial = hidden, landing = null)
        val wrote = stream(step.from, hidden, target)
        // Whole, as far as where it came from says now: a file still being written isn't. Another
        // app's shared file may not say its size, so it isn't asked.
        if (!state.outside && atSource { source.files.stat(step.from.path) }?.size != wrote) {
            throw FileFailed(Failure.Reason.Mismatch)
        }
        return name(step, hidden, target)
    }

    /**
     * Gives the whole [hidden] file its name: over what's there only if the user chose Replace, and
     * never over a file this job put there, or over the very file a move is moving.
     */
    private suspend fun name(step: CopyFile, hidden: String, target: Side): String {
        val there = if (step.replace) target.files.stat(step.path) else null
        // A file can't take a folder's place.
        if (there?.isFolder == true) throw FileSystemException.Exists(step.path)
        // The very file a move is moving, reached another way (a link, or two drawers on one
        // disk): then what this job just wrote beside it shows up beside the original too.
        val itself = state.kind == Job.Kind.Move && there != null && besideOriginal(step, hidden)
        val replace = there != null && step.path.lowercase() !in written && !itself
        return try {
            state = state.copy(landing = step.path)
            target.files.rename(hidden, step.path, replace)
            landed(step.path)
        } catch (_: FileSystemException.Exists) {
            beside(step.from, hidden, step.path, target)
        }
    }

    /** Whether [hidden], just written where [step]'s file goes, is in the folder it came from. */
    private suspend fun besideOriginal(step: CopyFile, hidden: String): Boolean =
        atSource { source.files.stat(join(parentOf(step.from.path), nameOf(hidden))) } != null

    /**
     * Keeps both when [path] turns out to be taken after all, say by a name that differs only in
     * case or that arrived while the job was paused: [hidden] takes the first free name, which a
     * resumed job remembers.
     */
    private suspend fun beside(from: Entry, hidden: String, path: String, target: Side): String {
        val folder = parentOf(path)
        val taken = target.files.list(folder).map { it.name.lowercase() }.toSet()
        val free = join(folder, freeName(nameOf(path), isFolder = false, taken))
        state =
            state.copy(
                landing = free,
                placed = state.placed + (from.path to Placement(free, replace = false)),
            )
        target.files.rename(hidden, free)
        state = state.copy(keptBoth = state.keptBoth + 1)
        return landed(free)
    }

    /** The file has its name at [path]: one this job wrote, so never one it replaces. */
    private fun landed(path: String): String {
        written += path.lowercase()
        state = state.copy(partial = null, landing = null)
        return path
    }

    /** Streams [from] into [path], this job's own hidden file, from its start. Says how much. */
    private suspend fun stream(from: Entry, path: String, target: Side): Long {
        var wrote = 0L
        atSource { source.files.read(from.path) }
            .use { input ->
                target.files.write(path, replace = true).use { output ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        wrote += read
                        progress { it.copy(bytesDone = it.bytesDone + read) }
                    }
                }
            }
        return wrote
    }

    // Deleting

    private suspend fun delete() {
        val order = mutableListOf<Entry>()
        for (pick in state.picks) {
            // A link goes on its own: what it points to isn't the user's to lose here.
            order +=
                if (pick.isFolder && !pick.isLink && pick.path !in done) inside(pick)
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
                entry.path in done ->
                    progress {
                        it.copy(itemsDone = it.itemsDone + 1, bytesDone = it.bytesDone + entry.size)
                    }
                // A folder that kept something it couldn't delete fails on its own: say it once.
                entry.isFolder && state.failures.any { it.path.isWithin(entry.path) } -> Unit
                else -> {
                    progress { it.copy(current = entry.name, currentPath = entry.path) }
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
                fail(folder, reasonOf(e), atSource = true)
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
            fail(entry, reasonOf(e), atSource = state.kind == Job.Kind.Delete)
        } catch (e: FileFailed) {
            fail(entry, e.reason, e.atSource)
        }
    }

    private fun finished(entry: Entry, bytes: Long) {
        done += entry.path
        state = state.copy(copied = state.copied - entry.path)
        progress { it.copy(itemsDone = it.itemsDone + 1, bytesDone = it.bytesDone + bytes) }
    }

    private fun fail(entry: Entry, reason: Failure.Reason, atSource: Boolean = false) {
        val failure = Failure(entry.name, entry.path, reason, atSource)
        state = state.copy(failures = state.failures + failure)
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
                    throw FileSystemException.Unreadable(state.progress.current ?: "", e)
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
        var next = change(state.progress)
        val (since, bytesThen) = speedFrom
        if (now - since >= SPEED_WINDOW_MILLIS) {
            val speed = (next.bytesDone - bytesThen) * MILLIS_PER_SECOND / (now - since)
            next = next.copy(bytesPerSecond = speed.coerceAtLeast(0))
            speedFrom = now to next.bytesDone
        }
        state = state.copy(progress = next)
        if (now - lastReport >= REPORT_EVERY_MILLIS || next.itemsDone == next.itemsTotal) {
            lastReport = now
            report(job)
        }
    }

    private inline fun tryOrNull(block: () -> Unit) {
        try {
            block()
        } catch (_: FileSystemException) {
            // Best effort: what's left behind is still there to see.
        }
    }

    companion object {
        const val CHUNK = 256 * 1024
        private const val RADIX = 36
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
        is FileSystemException.Unreachable -> Failure.Reason.Unreachable
        is FileSystemException.AuthFailed,
        is FileSystemException.BadKey -> Failure.Reason.LoginRefused
        // The share is gone, and what was in it with it.
        is FileSystemException.ShareMissing -> Failure.Reason.NotFound
        is FileSystemException.HostKeyUnknown,
        is FileSystemException.HostKeyChanged -> Failure.Reason.KeyChanged
    }
