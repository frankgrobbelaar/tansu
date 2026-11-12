package io.github.frankgrobbelaar.tansu.ui.servers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.ui.kit.TansuField
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar
import kotlinx.coroutines.launch

/** Adding or editing a server, wired to its ViewModel and the phone's file picker for keys. */
@Composable
fun ServerFormRoute(
    kind: Server.Kind,
    serverId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerFormViewModel =
        viewModel(factory = ServerFormViewModel.factory(kind, serverId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val localNetwork = rememberLocalNetworkAccess()
    val scope = rememberCoroutineScope()
    val pickKey =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val key = readKeyFile(context, uri)
                    viewModel.useKey(key?.name.orEmpty(), key?.text.orEmpty())
                }
            }
        }

    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.done) { if (state.done) done() }

    ServerFormScreen(
        state = state,
        onEdit = viewModel::edit,
        onPassword = viewModel::setPassword,
        onPassphrase = viewModel::setPassphrase,
        onPickKey = { pickKey.launch(arrayOf("*/*")) },
        onConnect = { localNetwork.ask(viewModel::connect) },
        onCancel = viewModel::cancel,
        onTrust = viewModel::trust,
        onBack = onBack,
        modifier = modifier,
        localNetworkBlocked = !localNetwork.allowed,
    )
}

/** The fields for one kind of server, then Connect, or what's happening while it connects. */
@Composable
fun ServerFormScreen(
    state: ServerFormUiState,
    onEdit: ((ServerDraft) -> ServerDraft) -> Unit,
    onPassword: (String) -> Unit,
    onPassphrase: (String) -> Unit,
    onPickKey: () -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onTrust: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    localNetworkBlocked: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        TansuTopBar(
            title =
                stringResource(
                    if (state.kind == Server.Kind.Smb) R.string.form_title_smb
                    else R.string.form_title_sftp
                ),
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
                    .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 20.dp)
        ) {
            when (state.kind) {
                Server.Kind.Smb -> SmbFields(state, onEdit, onPassword)
                Server.Kind.Sftp -> SftpFields(state, onEdit, onPassword, onPassphrase, onPickKey)
            }
        }
        ConnectBar(
            state = state,
            onConnect = onConnect,
            onCancel = onCancel,
            localNetworkBlocked = localNetworkBlocked,
        )
    }
    val trust = state.step as? ServerFormUiState.Step.Trust
    if (trust != null) {
        TrustDialog(
            name = state.draft.name.ifBlank { state.hostName },
            host = state.hostName,
            trust = trust,
            onTrust = onTrust,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun SmbFields(
    state: ServerFormUiState,
    onEdit: ((ServerDraft) -> ServerDraft) -> Unit,
    onPassword: (String) -> Unit,
    modifier: Modifier = Modifier,
) =
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val draft = state.draft
        NameField(draft, onEdit)
        TansuField(
            label = stringResource(R.string.field_host),
            value = draft.host,
            onValueChange = { text -> onEdit { it.copy(host = text) } },
            hint = stringResource(R.string.field_host_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        TansuField(
            label = stringResource(R.string.field_share),
            value = draft.share,
            onValueChange = { text -> onEdit { it.copy(share = text) } },
        )
        FolderField(draft, onEdit)
        val guest = draft.signIn == Server.SignIn.Guest
        AnimatedVisibility(visible = !guest) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UsernameField(draft, onEdit)
                TansuField(
                    label = stringResource(R.string.field_password),
                    value = state.password,
                    onValueChange = onPassword,
                    secret = true,
                )
            }
        }
        GuestSwitch(
            guest = guest,
            onGuest = { on ->
                onEdit {
                    it.copy(signIn = if (on) Server.SignIn.Guest else Server.SignIn.Password)
                }
            },
        )
    }

@Composable
private fun SftpFields(
    state: ServerFormUiState,
    onEdit: ((ServerDraft) -> ServerDraft) -> Unit,
    onPassword: (String) -> Unit,
    onPassphrase: (String) -> Unit,
    onPickKey: () -> Unit,
    modifier: Modifier = Modifier,
) =
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val draft = state.draft
        NameField(draft, onEdit)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TansuField(
                label = stringResource(R.string.field_host),
                value = draft.host,
                onValueChange = { text -> onEdit { it.copy(host = text) } },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TansuField(
                label = stringResource(R.string.field_port),
                value = draft.port,
                onValueChange = { text -> onEdit { it.copy(port = text.filter(Char::isDigit)) } },
                modifier = Modifier.width(88.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        FolderField(draft, onEdit)
        UsernameField(draft, onEdit)
        SignInChoice(
            signIn = draft.signIn,
            onSignIn = { signIn -> onEdit { it.copy(signIn = signIn) } },
        )
        if (draft.signIn == Server.SignIn.Key) {
            KeyFileCard(keyName = draft.keyName.takeIf { state.hasKey }, onPick = onPickKey)
            TansuField(
                label = stringResource(R.string.field_passphrase),
                value = state.passphrase,
                onValueChange = onPassphrase,
                hint = stringResource(R.string.field_passphrase_hint),
                secret = true,
            )
        } else {
            TansuField(
                label = stringResource(R.string.field_password),
                value = state.password,
                onValueChange = onPassword,
                secret = true,
            )
        }
    }

@Composable
private fun NameField(draft: ServerDraft, onEdit: ((ServerDraft) -> ServerDraft) -> Unit) {
    TansuField(
        label = stringResource(R.string.field_name),
        value = draft.name,
        onValueChange = { text -> onEdit { it.copy(name = text) } },
        placeholder = draft.host.trim().ifEmpty { null },
    )
}

@Composable
private fun FolderField(draft: ServerDraft, onEdit: ((ServerDraft) -> ServerDraft) -> Unit) {
    TansuField(
        label = stringResource(R.string.field_folder),
        value = draft.folder,
        onValueChange = { text -> onEdit { it.copy(folder = text) } },
        placeholder = stringResource(R.string.optional),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

@Composable
private fun UsernameField(draft: ServerDraft, onEdit: ((ServerDraft) -> ServerDraft) -> Unit) {
    TansuField(
        label = stringResource(R.string.field_username),
        value = draft.username,
        onValueChange = { text -> onEdit { it.copy(username = text) } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
}

@Composable
private fun GuestSwitch(guest: Boolean, onGuest: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .toggleable(value = guest, role = Role.Switch, onValueChange = onGuest)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sign_in_guest),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = guest, onCheckedChange = null)
    }
}

@Composable
private fun SignInChoice(
    signIn: Server.SignIn,
    onSignIn: (Server.SignIn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val choices = listOf(Server.SignIn.Password, Server.SignIn.Key)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.sign_in_with),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = signIn == choice,
                    onClick = { onSignIn(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    colors =
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.primaryContainer,
                            activeContentColor = colors.onPrimaryContainer,
                            activeBorderColor = colors.outlineVariant,
                            inactiveBorderColor = colors.outlineVariant,
                        ),
                    label = {
                        Text(
                            stringResource(
                                if (choice == Server.SignIn.Key) R.string.sign_in_key
                                else R.string.sign_in_password
                            )
                        )
                    },
                )
            }
        }
    }
}
