package io.github.frankgrobbelaar.tansu.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.jobs.Jobs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Transfers tab's jobs, grouped the way the board groups them. */
data class TransfersUiState(
    val now: Job? = null,
    val next: List<Job> = emptyList(),
    val needsLook: List<Job> = emptyList(),
    val earlier: List<Job> = emptyList(),
) {
    val empty: Boolean
        get() = now == null && next.isEmpty() && needsLook.isEmpty() && earlier.isEmpty()
}

/** What's running, what's next, what needs a look, and what finished, with their controls. */
class TransfersViewModel(private val jobs: Jobs) : ViewModel() {

    val state: StateFlow<TransfersUiState> =
        jobs.all
            .map(::group)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS),
                group(jobs.all.value),
            )

    fun job(id: Long): StateFlow<Job?> =
        jobs.all
            .map { all -> all.find { it.id == id } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS),
                jobs.all.value.find { it.id == id },
            )

    fun pause(id: Long) = jobs.pause(id)

    fun resume(id: Long) = jobs.resume(id)

    fun cancel(id: Long) = jobs.cancel(id)

    fun retry(id: Long) = jobs.retry(id)

    fun clearFinished() = jobs.clearFinished()

    companion object {
        private const val STOP_AFTER_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                TransfersViewModel((this[APPLICATION_KEY] as TansuApplication).container.jobs)
            }
        }
    }
}

/** Newest finished first; the queue in the order it runs. */
internal fun group(all: List<Job>): TransfersUiState {
    val needsLook = all.filter {
        val status = it.status
        (status is Job.Status.Paused && status.lost != null) ||
            (status == Job.Status.Done && it.failures.isNotEmpty())
    }
    return TransfersUiState(
        now = all.firstOrNull { it.status == Job.Status.Running },
        next =
            all.filter {
                it.status == Job.Status.Queued || it.status == Job.Status.Paused(lost = null)
            },
        needsLook = needsLook,
        earlier =
            all.filter { !it.active && it !in needsLook }.sortedByDescending { it.finishedAt },
    )
}
