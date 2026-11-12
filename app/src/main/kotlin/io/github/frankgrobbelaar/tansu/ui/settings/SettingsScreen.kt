package io.github.frankgrobbelaar.tansu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.frankgrobbelaar.tansu.Choices
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.Theme
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.ui.browse.SortSheet
import io.github.frankgrobbelaar.tansu.ui.browse.label
import io.github.frankgrobbelaar.tansu.ui.browse.openOutside
import io.github.frankgrobbelaar.tansu.ui.browse.orderLabel
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar
import io.github.frankgrobbelaar.tansu.updates.Update

@Composable
fun SettingsRoute(
    onAddServer: () -> Unit,
    onEditServer: (Server) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android's installer takes it from here: it asks to confirm, and checks the signature.
    LaunchedEffect(state.install) {
        val apk = state.install ?: return@LaunchedEffect
        openOutside(context, apk)
        viewModel.installHandled()
    }
    SettingsScreen(
        state = state,
        actions =
            SettingsActions(
                onTheme = viewModel::setTheme,
                onDynamicColor = viewModel::setDynamicColor,
                onShowHidden = viewModel::setShowHidden,
                onSort = viewModel::setSort,
                onCheckUpdates = viewModel::setCheckUpdates,
                onCheckNow = viewModel::checkNow,
                onDownload = viewModel::downloadAndInstall,
                onCancelDownload = viewModel::cancelDownload,
                onInstall = viewModel::install,
                onAddServer = onAddServer,
                onEditServer = onEditServer,
            ),
        modifier = modifier,
    )
}

/** Short and grouped: how it looks, how Browse behaves, your servers, and the version you're on. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    val choices = state.choices
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(title = stringResource(R.string.nav_settings))
        Column(
            modifier =
                Modifier.weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)
        ) {
            if (state.update.hasCard) {
                UpdateCard(
                    update = state.update,
                    installed = state.version,
                    onDownload = actions.onDownload,
                    onCancel = actions.onCancelDownload,
                    onInstall = actions.onInstall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Group(stringResource(R.string.settings_appearance)) {
                Appearance(choices, onTheme = { choosingTheme = true }, actions.onDynamicColor)
            }
            Group(stringResource(R.string.nav_browse)) {
                BrowseChoices(choices, actions.onShowHidden, onOpenSort = { sorting = true })
            }
            Group(stringResource(R.string.settings_servers)) {
                state.servers.forEach { server ->
                    ServerSetting(server, onClick = { actions.onEditServer(server) })
                    RowDivider()
                }
                AddServer(onClick = actions.onAddServer)
            }
            Group(stringResource(R.string.settings_about)) { About(state, actions) }
        }
    }
    if (sorting) {
        SortSheet(sort = choices.sort, onSort = actions.onSort, onDismiss = { sorting = false })
    }
    if (choosingTheme) {
        ThemeDialog(
            current = choices.theme,
            onPick = {
                choosingTheme = false
                actions.onTheme(it)
            },
            onDismiss = { choosingTheme = false },
        )
    }
}

@Composable
private fun Appearance(
    choices: Choices,
    onTheme: () -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Setting(
            title = stringResource(R.string.settings_theme),
            detail = stringResource(choices.theme.label),
            modifier = Modifier.clickable(role = Role.Button, onClick = onTheme),
            trailing = { Chevron() },
        )
        RowDivider()
        Toggle(
            title = stringResource(R.string.settings_dynamic_color),
            detail = stringResource(R.string.settings_dynamic_color_detail),
            checked = choices.dynamicColor,
            onChange = onDynamicColor,
        )
    }
}

@Composable
private fun BrowseChoices(
    choices: Choices,
    onShowHiddenChange: (Boolean) -> Unit,
    onOpenSort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Toggle(
            title = stringResource(R.string.settings_show_hidden),
            detail = stringResource(R.string.settings_show_hidden_detail),
            checked = choices.showHidden,
            onChange = onShowHiddenChange,
        )
        RowDivider()
        Setting(
            title = stringResource(R.string.sort),
            detail =
                stringResource(choices.sort.by.label) +
                    ", " +
                    orderLabel(choices.sort.by, choices.sort.descending),
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSort),
            trailing = { Chevron() },
        )
    }
}

@Composable
private fun About(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Setting(
            title = stringResource(R.string.settings_version),
            trailing = {
                Text(
                    text = state.version,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        RowDivider()
        val checking = state.update == Update.Checking
        Setting(
            title = stringResource(R.string.settings_updates),
            detail = updatesDetail(state),
            modifier = Modifier.clickable(role = Role.Button, onClick = actions.onCheckNow),
            trailing = {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = TansuIcons.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        RowDivider()
        Toggle(
            title = stringResource(R.string.settings_check_daily),
            detail = stringResource(R.string.settings_check_daily_detail),
            checked = state.choices.checkUpdates,
            onChange = actions.onCheckUpdates,
        )
    }
}

/** Where updates stand, in a line. */
@Composable
private fun updatesDetail(state: SettingsUiState): String {
    val lastCheck = state.choices.lastUpdateCheck
    val day = Human.date(lastCheck, withTime = false).replaceFirstChar { it.lowercase() }
    return when (val update = state.update) {
        Update.Checking -> stringResource(R.string.updates_checking)
        Update.UpToDate -> stringResource(R.string.updates_up_to_date, day)
        Update.CheckFailed -> stringResource(R.string.updates_failed)
        Update.Unknown ->
            if (lastCheck > 0) stringResource(R.string.updates_checked, day)
            else stringResource(R.string.updates_never)
        is Update.Available -> stringResource(R.string.updates_new, update.release.version)
        is Update.Downloading -> stringResource(R.string.updates_new, update.release.version)
        is Update.Ready -> stringResource(R.string.updates_new, update.release.version)
        is Update.DownloadFailed -> stringResource(R.string.updates_new, update.release.version)
    }
}

/** A titled group of settings on one rounded surface. */
@Composable
private fun Group(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp).semantics {
                    heading()
                },
        )
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            content = content,
        )
    }
}

/** One setting: its name, a line about it, and what's at its end. */
@Composable
private fun Setting(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun Toggle(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Setting(
        title = title,
        detail = detail,
        modifier =
            modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun ServerSetting(server: Server, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val place =
        when (server.kind) {
            Server.Kind.Smb ->
                stringResource(
                    R.string.settings_server_smb,
                    (listOf(server.host, server.share) + server.folder.split('/'))
                        .filter { it.isNotEmpty() }
                        .joinToString(" › "),
                )
            Server.Kind.Sftp ->
                stringResource(R.string.settings_server_sftp, server.host, server.port)
        }
    Setting(
        title = server.name,
        detail = place,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        leading = {
            Tile {
                Icon(
                    imageVector =
                        if (server.kind == Server.Kind.Smb) TansuIcons.Nas else TansuIcons.Server,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailing = { Chevron() },
    )
}

/** Add a server, on a dashed tile: a drawer that isn't there yet. */
@Composable
private fun AddServer(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val edge = MaterialTheme.colorScheme.outline
    Setting(
        title = stringResource(R.string.add_server),
        titleColor = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        leading = {
            Box(
                modifier =
                    Modifier.size(34.dp).drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawRoundRect(
                            color = edge,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style =
                                Stroke(
                                    width = stroke,
                                    pathEffect =
                                        PathEffect.dashPathEffect(
                                            floatArrayOf(4.dp.toPx(), 3.dp.toPx())
                                        ),
                                ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TansuIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

@Composable
private fun Tile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier =
            modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = TansuIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun ThemeDialog(current: Theme, onPick: (Theme) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                Theme.entries.forEach { theme ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = theme == current,
                                    role = Role.RadioButton,
                                    onClick = { onPick(theme) },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == current, onClick = null)
                        Text(
                            text = stringResource(theme.label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val Theme.label: Int
    get() =
        when (this) {
            Theme.System -> R.string.theme_system
            Theme.Light -> R.string.theme_light
            Theme.Dark -> R.string.theme_dark
        }
