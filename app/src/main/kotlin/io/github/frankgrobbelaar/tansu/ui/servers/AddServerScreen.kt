package io.github.frankgrobbelaar.tansu.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar

/** The first step of adding a server: SMB or SFTP, each in one line. */
@Composable
fun AddServerScreen(
    onPick: (Server.Kind) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(
            title = stringResource(R.string.add_server_title),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(TansuIcons.Back, contentDescription = stringResource(R.string.back))
                }
            },
        )
        Column(
            modifier =
                Modifier.weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.add_server_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
            )
            KindOption(
                icon = TansuIcons.Nas,
                title = stringResource(R.string.kind_smb),
                body = stringResource(R.string.kind_smb_body),
                onClick = { onPick(Server.Kind.Smb) },
            )
            KindOption(
                icon = TansuIcons.Server,
                title = stringResource(R.string.kind_sftp),
                body = stringResource(R.string.kind_sftp_body),
                onClick = { onPick(Server.Kind.Sftp) },
            )
        }
        TailscaleTip(
            modifier =
                Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun KindOption(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.large)
                .background(colors.surfaceContainerLowest)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 18.dp, end = 14.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = TansuIcons.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TailscaleTip(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = TansuIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.tailscale_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
