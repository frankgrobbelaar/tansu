package io.github.frankgrobbelaar.tansu.ui.format

import android.content.res.Resources
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.jobs.Failure
import io.github.frankgrobbelaar.tansu.jobs.Job

/** What a job is doing or did, in the words the boards use, for screens and notifications. */
object JobWords {
    /** "Copying 3 files to NAS", "Deleted 12 items from NAS". */
    fun title(job: Job, res: Resources): String {
        val count = job.picks.size
        val files = job.picks.none { it.isFolder }
        val finished = job.status == Job.Status.Done
        val id = TITLES.getValue(Triple(job.kind, finished, files))
        val drawer = job.to?.drawerName ?: job.from.drawerName
        return res.getQuantityString(id, count, count, drawer)
    }

    /** Each title by kind, whether it's finished, and whether it's only files. */
    private val TITLES =
        mapOf(
            Triple(Job.Kind.Copy, false, true) to R.plurals.job_copying_files,
            Triple(Job.Kind.Copy, false, false) to R.plurals.job_copying_items,
            Triple(Job.Kind.Copy, true, true) to R.plurals.job_copied_files,
            Triple(Job.Kind.Copy, true, false) to R.plurals.job_copied_items,
            Triple(Job.Kind.Move, false, true) to R.plurals.job_moving_files,
            Triple(Job.Kind.Move, false, false) to R.plurals.job_moving_items,
            Triple(Job.Kind.Move, true, true) to R.plurals.job_moved_files,
            Triple(Job.Kind.Move, true, false) to R.plurals.job_moved_items,
            Triple(Job.Kind.Delete, false, true) to R.plurals.job_deleting_files,
            Triple(Job.Kind.Delete, false, false) to R.plurals.job_deleting_items,
            Triple(Job.Kind.Delete, true, true) to R.plurals.job_deleted_files,
            Triple(Job.Kind.Delete, true, false) to R.plurals.job_deleted_items,
        )

    /** The line under the title: how far, how long, or how it ended. */
    fun detail(job: Job, res: Resources): String {
        val progress = job.progress
        return when (val status = job.status) {
            Job.Status.Queued -> res.getString(R.string.job_waiting, Human.size(job.pickedBytes))
            is Job.Status.Paused ->
                if (status.lost != null) res.getString(R.string.job_paused_lost, status.lost)
                else res.getString(R.string.job_paused)
            Job.Status.Running ->
                when {
                    progress.counting -> res.getString(R.string.job_getting_ready)
                    job.kind == Job.Kind.Delete ->
                        res.getString(
                            R.string.job_items_of,
                            progress.itemsDone,
                            progress.itemsTotal,
                        )
                    else -> sizeAndTime(job, res)
                }
            Job.Status.Done -> ending(job, res)
            Job.Status.Cancelled -> res.getString(R.string.job_cancelled)
        }
    }

    private fun sizeAndTime(job: Job, res: Resources): String {
        val progress = job.progress
        val size =
            res.getString(
                R.string.job_size_of,
                Human.size(progress.bytesDone),
                Human.size(progress.bytesTotal),
            )
        val left = progress.secondsLeft ?: return size
        return res.getString(R.string.job_size_and_time, size, timeLeft(left, res))
    }

    /** "a few seconds left", "1 min left", "about 2 hours left". */
    fun timeLeft(seconds: Long, res: Resources): String =
        when {
            seconds < MINUTE -> res.getString(R.string.time_seconds_left)
            seconds < HOUR -> {
                val minutes = (seconds + MINUTE / 2) / MINUTE
                res.getQuantityString(R.plurals.time_minutes_left, minutes.toInt(), minutes)
            }
            else -> {
                val hours = (seconds + HOUR / 2) / HOUR
                res.getQuantityString(R.plurals.time_hours_left, hours.toInt(), hours)
            }
        }

    private fun ending(job: Job, res: Resources): String {
        val failed = job.failures.size
        val succeeded = job.progress.itemsDone - failed
        return when {
            failed > 0 ->
                res.getQuantityString(R.plurals.job_some_failed, failed, succeeded, failed)
            job.kind == Job.Kind.Delete ->
                res.getString(R.string.job_freed, Human.size(job.progress.bytesDone))
            job.keptBoth > 0 ->
                res.getQuantityString(
                    R.plurals.job_done_kept,
                    job.keptBoth,
                    Human.size(job.progress.bytesDone),
                    job.keptBoth,
                )
            else -> Human.size(job.progress.bytesDone)
        }
    }

    /** Why a file in [job] didn't make it, in plain words. */
    fun reason(failure: Failure, job: Job, res: Resources): String =
        reason(failure.reason, job.to?.drawerName ?: job.from.drawerName, res)

    /** Why something couldn't be done in [drawer], in plain words. */
    fun reason(reason: Failure.Reason, drawer: String, res: Resources): String =
        when (reason) {
            Failure.Reason.NotFound -> res.getString(R.string.failure_not_found)
            Failure.Reason.AccessDenied -> res.getString(R.string.failure_access_denied, drawer)
            Failure.Reason.NoSpace -> res.getString(R.string.failure_no_space, drawer)
            Failure.Reason.Exists -> res.getString(R.string.failure_exists)
            Failure.Reason.Unreadable -> res.getString(R.string.failure_unreadable)
            Failure.Reason.Unreachable -> res.getString(R.string.failure_unreachable, drawer)
            Failure.Reason.IntoItself -> res.getString(R.string.failure_into_itself)
            Failure.Reason.Mismatch -> res.getString(R.string.failure_mismatch)
        }

    private const val MINUTE = 60L
    private const val HOUR = 3_600L
}
