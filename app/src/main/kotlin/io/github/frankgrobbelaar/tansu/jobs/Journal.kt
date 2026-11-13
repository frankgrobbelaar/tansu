package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.FileSystem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Where jobs are written down as they go, so one still to finish when Android ends tansu's process
 * comes back where it was. A job's log is the job as it stood when it last started, then each
 * change since, each written before the step on a drawer that depends on it.
 */
interface Journal {
    /** Every job written down and not ended, as its log has it, oldest first. */
    fun saved(): List<Job>

    /** Writes [job] down afresh: everything it knows so far. */
    fun begin(job: Job)

    /** Adds [changes] to job [id]'s log, in one go. */
    fun note(id: Long, changes: List<Change>)

    /** Forgets job [id], which has ended: there's nothing to carry on with. */
    fun end(id: Long)

    /** Keeps nothing: jobs last as long as the process. */
    object None : Journal {
        override fun saved(): List<Job> = emptyList()

        override fun begin(job: Job) = Unit

        override fun note(id: Long, changes: List<Change>) = Unit

        override fun end(id: Long) = Unit
    }
}

/** One change to what a job must remember to carry on: a line in its log. */
@Serializable
sealed interface Change {
    /** [path], where the files come from, is through. */
    @Serializable @SerialName("done") data class Done(val path: String) : Change

    /** The job gave a file the name [path], in lower case: one it never replaces. */
    @Serializable @SerialName("written") data class Written(val path: String) : Change

    /** The file the job named [path] is gone again. */
    @Serializable @SerialName("unwritten") data class Unwritten(val path: String) : Change

    /** Where [from] goes; null once that's forgotten. */
    @Serializable
    @SerialName("placed")
    data class Placed(val from: String, val placement: Placement?) : Change

    /** Where a move's copy of [from] is, made but not yet checked; null once that's settled. */
    @Serializable
    @SerialName("copied")
    data class Copied(val from: String, val path: String?) : Change

    /** When [from] last changed, as it said right after it was copied; null once it's through. */
    @Serializable
    @SerialName("asCopied")
    data class AsCopied(val from: String, val modified: Long?) : Change

    /** A moved folder the job has begun removing from where it was. */
    @Serializable @SerialName("removing") data class Removing(val folder: String) : Change

    /** The file under way: its hidden name, whose it is, as of when, and where it's being named. */
    @Serializable
    @SerialName("state")
    data class State(
        val partial: String?,
        val partialOf: String?,
        val partialAs: Stamp?,
        val landing: String?,
        val keptBoth: Int,
    ) : Change
}

/**
 * What changed from [was] to [now], to write down. What's done and written isn't here: those are
 * written down as they happen.
 */
internal fun changesBetween(was: Job, now: Job): List<Change> = buildList {
    addAll(changed(was.placed, now.placed, Change::Placed))
    addAll(changed(was.copied, now.copied, Change::Copied))
    addAll(changed(was.asCopied, now.asCopied, Change::AsCopied))
    if (now.removing !== was.removing)
        (now.removing - was.removing).forEach { add(Change.Removing(it)) }
    val state = stateOf(now)
    if (state != stateOf(was)) add(state)
}

private fun stateOf(job: Job) =
    Change.State(job.partial, job.partialOf, job.partialAs, job.landing, job.keptBoth)

/** Each entry of [now] that [was] lacked or had otherwise, then each it lost, as null. */
private fun <V : Any> changed(
    was: Map<String, V>,
    now: Map<String, V>,
    change: (String, V?) -> Change,
): List<Change> =
    if (now === was) {
        emptyList()
    } else {
        now.filter { (key, value) -> was[key] != value }
            .map { (key, value) -> change(key, value) } +
            (was.keys - now.keys).map { change(it, null) }
    }

/**
 * What one run of a job has to write down for its [Journal]: the sets that grow with every file,
 * kept here, and each change to them and to the job since it was last written down.
 */
internal class Notes(job: Job, private val note: (List<Change>) -> Unit) {
    val done: MutableSet<String> = job.done.toHashSet()
    val written: MutableSet<String> = job.written.toHashSet()
    private var noted = job
    private val unnoted = mutableListOf<Change>()

    fun markDone(path: String) {
        done += path
        unnoted += Change.Done(path)
    }

    /** A file the job named, by its path in lower case: never one it replaces. */
    fun markWritten(path: String) {
        val name = path.lowercase()
        written += name
        unnoted += Change.Written(name)
    }

    fun unmarkWritten(path: String) {
        val name = path.lowercase()
        written -= name
        unnoted += Change.Unwritten(name)
    }

    /** Writes down what changed since last time, [now] being the job as it stands. */
    fun writeDown(now: Job) {
        val changes = unnoted + changesBetween(noted, now)
        if (changes.isEmpty()) return
        note(changes)
        unnoted.clear()
        noted = now
    }
}

/** [this] file system, doing [first] before each change made to it. */
internal fun FileSystem.before(first: () -> Unit): FileSystem {
    val files = this
    return object : FileSystem by files {
        override suspend fun write(path: String, replace: Boolean): OutputStream {
            first()
            return files.write(path, replace)
        }

        override suspend fun writeAt(path: String, offset: Long): OutputStream {
            first()
            return files.writeAt(path, offset)
        }

        override suspend fun createFolder(path: String) {
            first()
            files.createFolder(path)
        }

        override suspend fun rename(from: String, to: String, replace: Boolean) {
            first()
            files.rename(from, to, replace)
        }

        override suspend fun delete(path: String) {
            first()
            files.delete(path)
        }
    }
}

/**
 * Keeps each job's log as a file of JSON lines in [folder]. A line reaches the kernel as it's
 * written, so it outlasts tansu's process, which is what it's for; a phone that loses power may
 * lose the last few, as it may the files themselves. A log that can't be kept up is dropped: one
 * that's behind would bring its job back wrong, so that job only lasts as long as the process.
 */
class FileJournal(private val folder: File) : Journal {
    /** Jobs whose logs couldn't be kept up, until they're written down afresh. */
    private val dropped = mutableSetOf<Long>()

    @Synchronized
    override fun saved(): List<Job> =
        folder
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                // Anything else is a log that can't be read, or one that was being written afresh
                // as the process ended, whose old one is still there.
                val job = if (file.name.endsWith(LOG)) read(file) else null
                if (job == null) file.delete()
                job
            }
            .sortedBy { it.id }

    @Synchronized
    override fun begin(job: Job) {
        dropped -= job.id
        keep(job.id) {
            folder.mkdirs()
            // Written beside, then put in the old one's place in one step, so a log is never half
            // written.
            val fresh = File(folder, "${job.id}$FRESH")
            fresh.writeText(json.encodeToString(job) + "\n")
            if (!fresh.renameTo(logOf(job.id))) throw IOException("Can't keep ${fresh.name}")
        }
    }

    @Synchronized
    override fun note(id: Long, changes: List<Change>) {
        val log = logOf(id)
        if (changes.isEmpty() || id in dropped || !log.exists()) return
        val lines = changes.joinToString("") { json.encodeToString(it) + "\n" }
        keep(id) { FileOutputStream(log, true).use { it.write(lines.toByteArray()) } }
    }

    @Synchronized
    override fun end(id: Long) {
        dropped -= id
        logOf(id).delete()
    }

    private fun keep(id: Long, write: () -> Unit) {
        try {
            write()
        } catch (_: IOException) {
            dropped += id
            logOf(id).delete()
        }
    }

    private fun logOf(id: Long) = File(folder, "$id$LOG")

    /** The job [file] holds, or null if it can't be read. A last line cut short is left out. */
    private fun read(file: File): Job? =
        try {
            val lines = file.readLines()
            val replay = Replay(json.decodeFromString<Job>(lines.first()))
            for (line in lines.drop(1)) {
                // Only the last line can be cut short, by the process ending as it was written.
                val change = decodeOrNull(line) ?: break
                replay.make(change)
            }
            replay.job()
        } catch (_: IOException) {
            null
        } catch (_: NoSuchElementException) {
            // Empty.
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            // Not a job of this version's making.
            null
        }

    private fun decodeOrNull(line: String): Change? =
        try {
            json.decodeFromString<Change>(line)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val LOG = ".log"
        const val FRESH = ".new"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A job read back from its log, a change at a time. */
private class Replay(private val job: Job) {
    private val done = job.done.toMutableSet()
    private val written = job.written.toMutableSet()
    private val placed = job.placed.toMutableMap()
    private val copied = job.copied.toMutableMap()
    private val asCopied = job.asCopied.toMutableMap()
    private val removing = job.removing.toMutableSet()
    private var state = stateOf(job)

    fun make(change: Change) {
        when (change) {
            is Change.Done -> done += change.path
            is Change.Written -> written += change.path
            is Change.Unwritten -> written -= change.path
            is Change.Placed -> placed.putOrRemove(change.from, change.placement)
            is Change.Copied -> copied.putOrRemove(change.from, change.path)
            is Change.AsCopied -> asCopied.putOrRemove(change.from, change.modified)
            is Change.Removing -> removing += change.folder
            is Change.State -> state = change
        }
    }

    fun job(): Job =
        job.copy(
            done = done,
            written = written,
            placed = placed,
            copied = copied,
            asCopied = asCopied,
            removing = removing,
            partial = state.partial,
            partialOf = state.partialOf,
            partialAs = state.partialAs,
            landing = state.landing,
            keptBoth = state.keptBoth,
        )

    /** Puts [value] at [key], or takes [key] away for null. */
    private fun <V : Any> MutableMap<String, V>.putOrRemove(key: String, value: V?) {
        if (value == null) remove(key) else put(key, value)
    }
}
