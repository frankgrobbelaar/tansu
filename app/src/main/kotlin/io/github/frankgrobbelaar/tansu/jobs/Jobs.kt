package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The job queue. Jobs run one at a time, in the order they were started, and each can be paused,
 * resumed, cancelled or retried. [onBusy] is told whenever there's work, so the app can stay alive
 * for it.
 */
class Jobs(
    private val drawers: StateFlow<List<Drawer>>,
    private val scope: CoroutineScope,
    private val onBusy: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val jobs = MutableStateFlow<List<Job>>(emptyList())
    private val ids = AtomicLong()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val stops = ConcurrentHashMap<Long, Stop>()
    private val outside = ConcurrentHashMap<String, Side>()

    @Volatile private var running: Running? = null

    /** Every job since the app started, oldest first. */
    val all: StateFlow<List<Job>> = jobs.asStateFlow()

    init {
        scope.launch {
            for (signal in wake) work()
        }
    }

    fun copy(picks: List<Entry>, from: Where, to: Where, clashes: Map<String, Clash>): Long =
        add(Job(ids.incrementAndGet(), Job.Kind.Copy, picks, from, to, clashes))

    fun move(picks: List<Entry>, from: Where, to: Where, clashes: Map<String, Clash>): Long =
        add(Job(ids.incrementAndGet(), Job.Kind.Move, picks, from, to, clashes))

    /**
     * Copies files from outside the drawers, such as files shared with tansu, read from [files].
     * [from]'s drawer id names them for this job alone.
     */
    fun copyIn(files: FileSystem, picks: List<Entry>, from: Where, to: Where): Long {
        outside[from.drawerId] = Side(files, remote = false, name = from.drawerName)
        return copy(picks, from, to, emptyMap())
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
        stop(id, Stop.Cancel) {
            if (it.active) it.copy(status = Job.Status.Cancelled, finishedAt = clock()) else it
        }
    }

    /** Runs a finished job again for what didn't make it; what arrived is left alone. */
    fun retry(id: Long) {
        change(id) {
            if (it.status == Job.Status.Done && it.failures.isNotEmpty()) {
                it.copy(status = Job.Status.Queued, finishedAt = null)
            } else {
                it
            }
        }
        nudge()
    }

    /** Forgets the jobs that have finished. */
    fun clearFinished() {
        jobs.update { list -> list.filter { it.active } }
    }

    private fun add(job: Job): Long {
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
            put(job.copy(status = Job.Status.Cancelled, finishedAt = clock()))
            return
        }
        val run = JobRun(job, source, target, report = ::put, clock = clock, wait = wait)
        val work = scope.async(io) { run.run() }
        running = Running(job.id, work)
        try {
            work.await()
        } catch (e: ConnectionLost) {
            put(run.job.copy(status = Job.Status.Paused(lost = e.name)))
        } catch (_: CancellationException) {
            settle(run, target, stops.remove(job.id))
            // The engine itself is shutting down: let it.
            currentCoroutineContext().ensureActive()
        } finally {
            running = null
        }
    }

    /** Leaves a stopped job paused, or cancelled without the file it was half way through. */
    private suspend fun settle(run: JobRun, target: Side?, stop: Stop?) {
        if (stop == Stop.Cancel) {
            run.inFlight?.let { path ->
                try {
                    target?.files?.delete(path)
                } catch (_: FileSystemException) {
                    // It may never have been created.
                }
            }
            put(run.job.copy(status = Job.Status.Cancelled, finishedAt = clock()))
        } else {
            put(run.job.copy(status = Job.Status.Paused()))
        }
    }

    private fun sideOf(where: Where): Side? =
        outside[where.drawerId]
            ?: drawers.value
                .find { it.id == where.drawerId }
                ?.let { Side(it.fileSystem, remote = it.kind != Drawer.Kind.Phone, name = it.name) }

    private enum class Stop {
        Pause,
        Cancel,
    }

    private class Running(val id: Long, val work: Deferred<Job>)
}
