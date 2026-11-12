package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.kit.EmptyState
import io.github.frankgrobbelaar.tansu.ui.kit.HostKeyBox
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/** Why Browse can't show the folder, in plain words, with the next step to take. */
@Composable
internal fun ProblemState(
    state: BrowseUiState,
    actions: BrowseActions,
    localNetworkBlocked: Boolean,
) {
    val name = state.drawer.name
    when (val problem = state.problem) {
        null -> Unit
        BrowseUiState.Problem.NoAccess ->
            Problem(
                icon = TansuIcons.Lock,
                title = stringResource(R.string.browse_no_access_title),
                body = stringResource(R.string.browse_no_access_body),
                main = Choice(stringResource(R.string.grant_access), actions.onGrantAccess),
            )
        BrowseUiState.Problem.Locked ->
            Problem(
                icon = TansuIcons.Lock,
                title = stringResource(R.string.browse_locked_title),
                body =
                    if (state.onServer) stringResource(R.string.browse_server_locked_body, name)
                    else stringResource(R.string.browse_locked_body),
                main = wayOut(state, actions),
            )
        BrowseUiState.Problem.Missing ->
            Problem(
                icon = TansuIcons.MissingFolder,
                title = stringResource(R.string.browse_missing_title),
                body = stringResource(R.string.browse_missing_body),
                main = wayOut(state, actions),
            )
        BrowseUiState.Problem.Unreadable ->
            Problem(
                icon = TansuIcons.Alert,
                title = stringResource(R.string.browse_unreadable_title),
                body = stringResource(R.string.browse_unreadable_body),
                main = Choice(stringResource(R.string.try_again), actions.onRetry),
            )
        else -> ServerProblem(problem, name, actions, localNetworkBlocked)
    }
}

/** The problems that are the whole server's, not one folder's. */
@Composable
private fun ServerProblem(
    problem: BrowseUiState.Problem,
    name: String,
    actions: BrowseActions,
    localNetworkBlocked: Boolean,
) {
    val tryAgain = Choice(stringResource(R.string.try_again), actions.onRetry)
    val editServer = Choice(stringResource(R.string.edit_server), actions.onEditServer)
    when (problem) {
        // Android 17 drops a home server's traffic until the user allows the local network.
        is BrowseUiState.Problem.Unreachable if localNetworkBlocked ->
            Problem(
                icon = TansuIcons.Lock,
                title = stringResource(R.string.local_network_title),
                body = stringResource(R.string.local_network_body, name),
                main = Choice(stringResource(R.string.allow), actions.onAllowLocalNetwork),
                other = tryAgain,
            )
        is BrowseUiState.Problem.Unreachable ->
            Problem(
                icon = TansuIcons.Offline,
                title = stringResource(R.string.server_unreachable_title, name),
                body = stringResource(R.string.server_unreachable_body, problem.host),
                main = tryAgain,
                other = editServer,
            )
        BrowseUiState.Problem.LoginRefused ->
            Problem(
                icon = TansuIcons.Key,
                title = stringResource(R.string.server_login_title, name),
                body = stringResource(R.string.server_login_body, name),
                main = Choice(stringResource(R.string.update_login), actions.onEditServer),
                other = tryAgain,
            )
        is BrowseUiState.Problem.ShareMissing ->
            Problem(
                icon = TansuIcons.MissingFolder,
                title = stringResource(R.string.share_missing_title, problem.share, name),
                body = stringResource(R.string.share_missing_body),
                main = editServer,
            )
        is BrowseUiState.Problem.KeyChanged -> KeyChanged(problem, name, actions)
        else -> Unit
    }
}

@Composable
private fun KeyChanged(
    problem: BrowseUiState.Problem.KeyChanged,
    name: String,
    actions: BrowseActions,
) {
    Problem(
        icon = TansuIcons.ShieldAlert,
        title = stringResource(R.string.key_changed_title, name),
        body = stringResource(R.string.key_changed_body),
        main = Choice(stringResource(R.string.keep_blocked), actions.onKeepBlocked),
        other = Choice(stringResource(R.string.trust_new_key), actions.onTrustKey),
        details = {
            if (problem.trusted.isNotEmpty()) {
                HostKeyBox(problem.trusted, label = stringResource(R.string.key_trusted))
            }
            HostKeyBox(
                problem.presented.fingerprint,
                label = stringResource(R.string.key_sent_now),
            )
        },
    )
}

/** Up a folder when there is one; at a server's top, its settings. */
@Composable
private fun wayOut(state: BrowseUiState, actions: BrowseActions): Choice =
    if (state.atRoot && state.onServer) {
        Choice(stringResource(R.string.edit_server), actions.onEditServer)
    } else {
        Choice(stringResource(R.string.go_up)) { actions.onUp() }
    }

private class Choice(val label: String, val onClick: () -> Unit)

@Composable
private fun Problem(
    icon: ImageVector,
    title: String,
    body: String,
    main: Choice,
    modifier: Modifier = Modifier,
    other: Choice? = null,
    details: @Composable () -> Unit = {},
) {
    EmptyState(
        icon = icon,
        title = title,
        body = body,
        modifier = modifier,
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details()
                Button(onClick = main.onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(main.label)
                }
                if (other != null) {
                    OutlinedButton(
                        onClick = other.onClick,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(other.label)
                    }
                }
            }
        },
    )
}

/** Browse's quiet offer, while there are no servers, to add the first one. */
@Composable
internal fun ServerOffer(onAdd: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                .clip(KiriCorners.Card)
                .background(colors.primaryContainer)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(36.dp).clip(KiriCorners.Tile).background(colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TansuIcons.Nas,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.server_offer_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.server_offer_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onPrimaryContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.not_now), color = colors.onPrimaryContainer)
            }
            Button(onClick = onAdd) { Text(stringResource(R.string.add_server)) }
        }
    }
}

/** Removing a server asks once, saying that nothing on the server changes. */
@Composable
internal fun RemoveServerDialog(name: String, onRemove: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.remove_server_title, name)) },
        text = { Text(stringResource(R.string.remove_server_body)) },
        confirmButton = {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
