package io.github.frankgrobbelaar.tansu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.format.JobWords
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.updates.Update

/** Whether Settings shows [UpdateCard]: there's a new version on offer, on its way or here. */
internal val Update.hasCard: Boolean
    get() =
        this is Update.Available ||
            this is Update.Downloading ||
            this is Update.Ready ||
            this is Update.DownloadFailed

/** A new version: what's in it and Download and install, then its progress, then Install. */
@Composable
internal fun UpdateCard(
    update: Update,
    installed: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.primaryContainer)
                .padding(18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onPrimaryContainer) {
            when (update) {
                is Update.Available -> {
                    val release = update.release
                    Heading(
                        title = stringResource(R.string.update_ready, release.version),
                        detail =
                            stringResource(
                                R.string.update_ready_detail,
                                installed,
                                Human.size(release.apkSize),
                            ),
                    )
                    Changes(release.changes)
                    WideButton(stringResource(R.string.update_download), onDownload)
                }
                is Update.Downloading -> Downloading(update, onCancel)
                is Update.Ready -> {
                    Heading(
                        title = stringResource(R.string.update_downloaded, update.release.version),
                        detail = stringResource(R.string.update_downloaded_detail),
                    )
                    WideButton(stringResource(R.string.update_install), onInstall)
                }
                is Update.DownloadFailed -> {
                    Heading(
                        title = stringResource(R.string.update_failed, update.release.version),
                        detail = stringResource(R.string.update_failed_detail),
                    )
                    WideButton(stringResource(R.string.update_try_again), onDownload)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Downloading(
    update: Update.Downloading,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val total = update.release.apkSize
    val size = stringResource(R.string.job_size_of, Human.size(update.bytesDone), Human.size(total))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(
            title = stringResource(R.string.update_downloading, update.release.version),
            detail =
                update.secondsLeft?.let {
                    stringResource(
                        R.string.job_size_and_time,
                        size,
                        JobWords.timeLeft(it, resources),
                    )
                } ?: size,
        )
        LinearProgressIndicator(
            progress = { if (total > 0) update.bytesDone.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        )
        Text(
            text = stringResource(R.string.update_confirm_next),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onCancel) {
            Text(
                text = stringResource(R.string.cancel),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun Heading(title: String, detail: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier.size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TansuIcons.Update,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** What's new, as the release lists it. */
@Composable
private fun Changes(changes: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        changes.forEach { change ->
            Row {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(14.dp),
                )
                Text(text = change, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WideButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().height(46.dp)) { Text(text) }
}
