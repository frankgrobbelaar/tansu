package io.github.frankgrobbelaar.tansu.ui.transfers

import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.isWithin
import io.github.frankgrobbelaar.tansu.jobs.Job

/** Where one thing picked for a job stands. */
enum class PickState {
    Waiting,
    Now,
    Done,
    Skipped,
    Failed,
}

/** Where [pick] stands in [job]: a folder by everything in it. */
internal fun stateOf(pick: Entry, job: Job): PickState {
    val within = { path: String -> path == pick.path || path.isWithin(pick.path) }
    val finished = job.status == Job.Status.Done
    return when {
        job.failures.any { within(it.path) } -> PickState.Failed
        pick.path in job.done -> PickState.Done
        job.status == Job.Status.Running && job.progress.currentPath?.let(within) == true ->
            PickState.Now
        // Skipped picks are the ones a copy or move never placed.
        finished && job.to != null && pick.path !in job.placed -> PickState.Skipped
        finished -> PickState.Done
        else -> PickState.Waiting
    }
}
