package io.github.frankgrobbelaar.tansu.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.ui.kit.HostKeyBox
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/** The private key in use, with Change; or a button to choose one. */
@Composable
internal fun KeyFileCard(keyName: String?, onPick: () -> Unit, modifier: Modifier = Modifier) {
    if (keyName == null) {
        OutlinedButton(onClick = onPick, modifier = modifier.fillMaxWidth().height(48.dp)) {
            Icon(TansuIcons.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.key_choose),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(KiriCorners.Field)
                .background(colors.surfaceContainerLowest)
                .border(1.dp, colors.outlineVariant, KiriCorners.Field)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(TansuIcons.Key, contentDescription = null, tint = colors.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = keyName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.key_from_phone),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        TextButton(onClick = onPick) { Text(stringResource(R.string.key_change)) }
    }
}

/** The foot of the form: what went wrong and Connect, or what's being checked and Cancel. */
@Composable
internal fun ConnectBar(
    state: ServerFormUiState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    localNetworkBlocked: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Column(
            modifier =
                Modifier.navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.step == ServerFormUiState.Step.Connecting) {
                Connecting(state)
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                state.failure?.let {
                    FailureNote(text = failureText(it, state, localNetworkBlocked))
                }
                Button(
                    onClick = onConnect,
                    enabled = state.canConnect,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(if (state.editing) R.string.save else R.string.connect))
                }
            }
        }
    }
}

@Composable
private fun Connecting(state: ServerFormUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
        Column {
            Text(
                text = stringResource(R.string.connecting_to, state.hostName),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text =
                    stringResource(
                        if (state.kind == Server.Kind.Smb) R.string.connecting_smb
                        else R.string.connecting_sftp
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailureNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(KiriCorners.Field)
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = TansuIcons.Alert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun failureText(
    failure: ServerFormUiState.Failure,
    state: ServerFormUiState,
    localNetworkBlocked: Boolean,
): String {
    val host = state.hostName
    return when (failure) {
        ServerFormUiState.Failure.Unreachable ->
            if (localNetworkBlocked) stringResource(R.string.fail_local_network, host)
            else stringResource(R.string.fail_unreachable, host)
        ServerFormUiState.Failure.LoginRefused -> stringResource(R.string.fail_login, host)
        ServerFormUiState.Failure.BadKey -> stringResource(R.string.fail_bad_key)
        ServerFormUiState.Failure.ShareMissing ->
            stringResource(R.string.fail_share, state.draft.share.trim(), host)
        ServerFormUiState.Failure.Locked -> stringResource(R.string.fail_locked, host)
        ServerFormUiState.Failure.FolderMissing ->
            stringResource(R.string.fail_folder, state.draft.folder.trim().ifEmpty { "/" }, host)
        ServerFormUiState.Failure.Unreadable -> stringResource(R.string.fail_unreadable, host)
        ServerFormUiState.Failure.NotAKey -> stringResource(R.string.fail_not_a_key)
    }
}

/**
 * The server's key, to check before tansu trusts it: the first time it connects, or when the key
 * changed since it was trusted.
 */
@Composable
internal fun TrustDialog(
    name: String,
    host: String,
    trust: ServerFormUiState.Step.Trust,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    val changed = trust.replaces.isNotEmpty()
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = if (changed) TansuIcons.ShieldAlert else TansuIcons.Shield,
                contentDescription = null,
            )
        },
        title = {
            Text(
                stringResource(
                    if (changed) R.string.key_changed_title else R.string.trust_title,
                    name,
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (changed) R.string.key_changed_body else R.string.trust_body,
                        host,
                    )
                )
                if (changed) {
                    HostKeyBox(trust.replaces, label = stringResource(R.string.key_trusted))
                }
                HostKeyBox(
                    trust.key.fingerprint,
                    label = if (changed) stringResource(R.string.key_sent_now) else trust.key.type,
                )
                if (!changed) Text(stringResource(R.string.trust_note))
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(stringResource(if (changed) R.string.trust_new_key else R.string.trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(if (changed) R.string.keep_blocked else R.string.cancel))
            }
        },
    )
}
