package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The job queue. Jobs run one at a time, in the order they were started, and each can be paused,
 * resumed, cancelled or retried. [onBusy] is told whenever there's work, so the app can stay alive
 * for it. Each job still to finish is kept in [journal], so one Android ended tansu in the middle
 * of comes back, paused, the next time it starts.
 */
class Jobs(
    private val drawers: StateFlow<List<Drawer>>,
    private val scope: CoroutineScope,
    private val onBusy: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val journal: Journal = Journal.None,
) {
    private val jobs = MutableStateFlow<List<Job>>(emptyList())
    private val ids = AtomicLong()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val stops = ConcurrentHashMap<Long, Stop>()
    private val outside = ConcurrentHashMap<String, Side>()

    @Volatile private var running: Running? = null

    /** Every job since the app started, and those it brought back, oldest first. */
    val all: StateFlow<List<Job>> = jobs.asStateFlow()

    init {
        jobs.value = broughtBack()
        ids.set(jobs.value.maxOfOrNull { it.id } ?: 0)
        scope.launch {
            for (signal in wake) work()
        }
    }

    fun copy(picks: List<Entry>, from: Where, to: Where, clashes: Map<String, Clash>): Long =
        add(Job(ids.incrementAndGet(), Job.Kind.Copy, picks, from, to, clashes, token = token()))

    fun move(picks: List<Entry>, from: Where, to: Where, clashes: Map<String, Clash>): Long =
        add(Job(ids.incrementAndGet(), Job.Kind.Move, picks, from, to, clashes, token = token()))

    /**
     * Copies files from outside the drawers, such as files shared with tansu, read from [files].
     * [from]'s drawer id names them for this job alone.
     */
    fun copyIn(files: FileSystem, picks: List<Entry>, from: Where, to: Where): Long {
        outside[from.drawerId] = Side(files, remote = false, name = from.drawerName)
        return add(
            Job(
                ids.incrementAndGet(),
                Job.Kind.Copy,
                picks,
                from,
                to,
                outside = true,
                token = token(),
            )
        )
    }

    fun delete(picks: List<Entry>, from: Where): Long =
        add(Job(ids.incrementAndGet(), Job.Kind.Delete, picks, from, to = null))

    fun pause(id: Long) {
        stop(id, Stop.Pause) {
            if (it.status == Job.Status.Queued) it.copy(status = Job.Status.Paused()) else it
        }
    }

    fun resume(id: Long) {
        change(id) {
            if (it.status is Job.Status.Paused) it.copy(status = Job.Status.Queued) else it
        }
        nudge()
    }

    fun cancel(id: Long) {
        // A job that stopped part-way through a file, paused or waiting to go again, leaves its
        // hidden half of it behind: that goes too. The running job cleans up after itself.
        val waiting = jobs.value.find { it.id == id && it.active && running?.id != id }
        stop(id, Stop.Cancel) {
            if (it.active) {
                journal.end(id)
                it.copy(
                    status = Job.Status.Cancelled,
                    finishedAt = clock(),
                    partial = null,
                    partialAs = null,
                )
            } else {
                it
            }
        }
        val partial = waiting?.partial ?: return
        val target = waiting.to?.let(::sideOf) ?: return
        scope.launch(io) { removeHalfWritten(target, partial) }
    }

    /** Runs a finished job again for what didn't make it; what arrived is left alone. */
    fun retry(id: Long) {
        change(id) {
            if (it.canRetry) it.copy(status = Job.Status.Queued, finishedAt = null) else it
        }
        nudge()
    }

    /** Forgets the jobs that have finished. */
    fun clearFinished() {
        jobs.update { list -> list.filter { it.active } }
    }

    /** A job's own mark for its hidden files, unlike any other job's on any phone. */
    private fun token(): String = UUID.randomUUID().toString().take(TOKEN_LENGTH)

    /**
     * The jobs Android ended tansu in the middle of, as their logs have them: paused, for the user
     * to carry on with. Another app's files are only lent while it shares them, so a share-in can't
     * go on: it's cancelled, without the half of a file it was writing.
     */
    private fun broughtBack(): List<Job> =
        journal.saved().map { job ->
            if (!job.outside) return@map job.copy(status = Job.Status.Paused())
            journal.end(job.id)
            val partial = job.partial
            val to = job.to
            if (partial != null && to != null) removeWhenThere(to, partial)
            job.copy(
                status = Job.Status.Cancelled,
                finishedAt = clock(),
                partial = null,
                partialAs = null,
            )
        }

    /** Removes a half-written file once its drawer is there: servers come after the phone. */
    private fun removeWhenThere(where: Where, partial: String) {
        scope.launch(io) {
            drawers.first { list -> list.any { it.id == where.drawerId } }
            sideOf(where)?.let { removeHalfWritten(it, partial) }
        }
    }

    private fun add(job: Job): Long {
        journal.begin(job)
        jobs.update { it + job }
        nudge()
        return job.id
    }

    private fun nudge() {
        onBusy()
        wake.trySend(Unit)
    }

    /** Stops the running job with [how]; a job that isn't running changes by [otherwise]. */
    private fun stop(id: Long, how: Stop, otherwise: (Job) -> Job) {
        val current = running
        if (current?.id == id) {
            stops[id] = how
            current.work.cancel()
        } else {
            change(id, otherwise)
        }
    }

    private fun change(id: Long, change: (Job) -> Job) {
        jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun put(job: Job) = change(job.id) { job }

    private suspend fun work() {
        while (true) {
            val next = jobs.value.firstOrNull { it.status == Job.Status.Queued } ?: return
            execute(next)
        }
    }

    private suspend fun execute(job: Job) {
        val source = sideOf(job.from)
        val target = job.to?.let(::sideOf)
        if (source == null || (job.to != null && target == null)) {
            // The drawer was removed while the job waited.
            journal.end(job.id)
            put(job.copy(status = Job.Status.Cancelled, finishedAt = clock()))
            return
        }
        // Written down afresh as it starts, so its log holds only what it knows now.
        journal.begin(job)
        val run =
            JobRun(
                job,
                source,
                target,
                report = ::put,
                note = { journal.note(job.id, it) },
                clock = clock,
                wait = wait,
            )
        val work = scope.async(io) { run.run() }
        running = Running(job.id, work)
        try {
            work.await()
            journal.end(job.id)
        } catch (e: ConnectionLost) {
            paused(run.job.copy(status = Job.Status.Paused(lost = e.name)))
        } catch (e: FileSystemException) {
            // The run puts what goes wrong against each file; anything else ends the job, saying
            // so.
            journal.end(job.id)
            put(run.job.ended(clock(), reasonOf(e)))
        } catch (_: CancellationException) {
            settle(run, target, stops.remove(job.id))
            // The engine itself is shutting down: let it.
            currentCoroutineContext().ensureActive()
        } finally {
            running = null
            // Another app's files are only lent while it shares them: done is done.
            if (job.outside && jobs.value.none { it.id == job.id && it.active }) {
                outside.remove(job.from.drawerId)
            }
        }
    }

    /** Leaves a stopped job paused, or cancelled without the file it was half way through. */
    private suspend fun settle(run: JobRun, target: Side?, stop: Stop?) {
        if (stop == Stop.Cancel) {
            journal.end(run.job.id)
            val partial = run.job.partial
            if (partial != null && target != null) removeHalfWritten(target, partial)
            put(
                run.job.copy(
                    status = Job.Status.Cancelled,
                    finishedAt = clock(),
                    partial = null,
                    partialAs = null,
                )
            )
        } else {
            paused(run.job.copy(status = Job.Status.Paused()))
        }
    }

    /** A job that stopped part-way, written down afresh with everything it knows. */
    private fun paused(job: Job) {
        journal.begin(job)
        put(job)
    }

    private suspend fun removeHalfWritten(target: Side, path: String) {
        try {
            target.files.delete(path)
        } catch (_: FileSystemException) {
            // Best effort: a server that's gone keeps it until the user deletes it.
        }
    }

    private fun sideOf(where: Where): Side? =
        outside[where.drawerId]
            ?: drawers.value
                .find { it.id == where.drawerId }
                ?.let { Side(it.fileSystem, remote = it.kind != Drawer.Kind.Phone, name = it.name) }

    private companion object {
        const val TOKEN_LENGTH = 8
    }

    private enum class Stop {
        Pause,
        Cancel,
    }

    private class Running(val id: Long, val work: Deferred<Job>)
}
