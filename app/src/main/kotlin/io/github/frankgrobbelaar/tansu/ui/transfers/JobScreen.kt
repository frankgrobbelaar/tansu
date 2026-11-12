package io.github.frankgrobbelaar.tansu.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.format.JobWords
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar

/** One job in detail: where it's going, how far it is, and what didn't make it. */
@Composable
fun JobRoute(
    id: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransfersViewModel = viewModel(factory = TransfersViewModel.Factory),
) {
    val flow = remember(id) { viewModel.job(id) }
    val job by flow.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val current = job
    if (current == null) {
        // Cleared away: there's nothing left to show.
        LaunchedEffect(Unit) { back() }
        return
    }
    JobScreen(
        job = current,
        actions =
            JobActions(
                onPause = { viewModel.pause(id) },
                onResume = { viewModel.resume(id) },
                onCancel = { viewModel.cancel(id) },
                onRetry = { viewModel.retry(id) },
                onBack = onBack,
            ),
        modifier = modifier,
    )
}

/** A job's controls, gathered so the screen's signature stays readable. */
class JobActions(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun JobScreen(job: Job, actions: JobActions, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(
            title = JobWords.title(job, resources),
            navigationIcon = {
                IconButton(onClick = actions.onBack) {
                    Icon(TansuIcons.Back, contentDescription = stringResource(R.string.back))
                }
            },
        )
        Column(
            modifier =
                Modifier.weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Progress(job)
            Place(stringResource(R.string.job_from), job.from.label)
            job.to?.let { Place(stringResource(R.string.job_to), it.label) }
            if (job.failures.isNotEmpty() && !job.active) Failures(job)
        }
        Controls(job, actions)
    }
}

@Composable
private fun Progress(job: Job, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val progress = job.progress
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${(progress.fraction * PERCENT).toInt()}%",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            progress.secondsLeft
                ?.takeIf { job.status == Job.Status.Running }
                ?.let {
                    Text(
                        JobWords.timeLeft(it, resources),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
        }
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Text(
            text = JobWords.detail(job, resources),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (job.status == Job.Status.Running && progress.bytesPerSecond > 0) {
            Text(
                text = stringResource(R.string.job_speed, Human.size(progress.bytesPerSecond)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        progress.current
            ?.takeIf { job.active }
            ?.let {
                Text(
                    stringResource(R.string.job_now_on, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

@Composable
private fun Place(label: String, where: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = where, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Failures(job: Job, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.job_not_done),
            style = MaterialTheme.typography.titleMedium,
        )
        job.failures.forEach { failure ->
            Column {
                Text(text = failure.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = JobWords.reason(failure, job, resources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Controls(job: Job, actions: JobActions, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val status = job.status
        when {
            status is Job.Status.Paused ->
                WideButton(stringResource(R.string.resume), actions.onResume, main = true)
            status == Job.Status.Running && job.kind != Job.Kind.Delete ->
                WideButton(stringResource(R.string.pause), actions.onPause, main = true)
            job.canRetry ->
                WideButton(
                    pluralStringResource(
                        R.plurals.retry_files,
                        job.failures.size,
                        job.failures.size,
                    ),
                    actions.onRetry,
                    main = true,
                )
        }
        if (job.active)
            WideButton(stringResource(R.string.cancel_job), actions.onCancel, main = false)
        else WideButton(stringResource(R.string.done), actions.onBack, main = false)
    }
}

@Composable
private fun WideButton(label: String, onClick: () -> Unit, main: Boolean) {
    val modifier = Modifier.fillMaxWidth().height(48.dp)
    if (main) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

private const val PERCENT = 100
