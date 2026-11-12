package io.github.frankgrobbelaar.tansu.ui.transfers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.ui.format.JobWords
import io.github.frankgrobbelaar.tansu.ui.kit.EmptyState
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/** Every copy, move and delete: what's running, what's next, what needs a look, what finished. */
@Composable
fun TransfersRoute(
    onOpenJob: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransfersViewModel = viewModel(factory = TransfersViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransfersScreen(state = state, onOpenJob = onOpenJob, modifier = modifier)
}

@Composable
fun TransfersScreen(
    state: TransfersUiState,
    onOpenJob: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(title = stringResource(R.string.nav_transfers))
        if (state.empty) {
            EmptyState(
                icon = TansuIcons.Transfers,
                title = stringResource(R.string.transfers_empty_title),
                body = stringResource(R.string.transfers_empty_body),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                section(R.string.transfers_now, listOfNotNull(state.now), onOpenJob)
                section(R.string.transfers_next, state.next, onOpenJob)
                section(R.string.transfers_needs_look, state.needsLook, onOpenJob)
                section(R.string.transfers_earlier, state.earlier, onOpenJob)
            }
        }
    }
}

private fun LazyListScope.section(title: Int, jobs: List<Job>, onOpenJob: (Long) -> Unit) {
    if (jobs.isEmpty()) return
    item(key = "title-$title") { SectionTitle(stringResource(title)) }
    items(jobs, key = { it.id }) { job ->
        JobRow(job = job, onClick = { onOpenJob(job.id) }, modifier = Modifier.animateItem())
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun JobRow(job: Job, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(KiriCorners.Card)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = JobWords.title(job, resources),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = JobWords.detail(job, resources),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (job.failures.isNotEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (job.status == Job.Status.Running) {
            LinearProgressIndicator(
                progress = { job.progress.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp),
            )
        }
    }
}
