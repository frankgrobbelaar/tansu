package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.jobs.Clash
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.kit.TansuField

/** Deleting asks once: what, how much and where, and that there's no undo. */
@Composable
internal fun DeleteDialog(
    picked: List<Entry>,
    drawer: String,
    folder: String,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val size = Human.size(picked.sumOf { it.size })
    val body =
        if (picked.any { it.isFolder }) R.string.delete_body_folders else R.string.delete_body
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(pluralStringResource(R.plurals.delete_title, picked.size, picked.size, drawer))
        },
        text = { Text(stringResource(body, size, folder)) },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Asks for a name: a new folder's, or a new one for a file, whose [extension] stays as it is. A
 * name with a slash, or none at all, can't be used.
 */
@Composable
internal fun NameDialog(
    title: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    initial: String = "",
    extension: String = "",
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val usable = name.isNotBlank() && '/' !in name && '\\' !in name
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            TansuField(
                label = stringResource(R.string.rename_name),
                value = name,
                onValueChange = { name = it },
                hint =
                    when {
                        name.isNotEmpty() && !usable -> stringResource(R.string.name_not_allowed)
                        extension.isNotEmpty() -> stringResource(R.string.rename_keeps_extension)
                        else -> null
                    },
                suffix = extension,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim() + extension) }, enabled = usable) {
                Text(confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * A pasted name that's already in the folder: both files side by side, and one question. Keep both
 * is the safe answer; the box settles the [rest] of the clashes the same way.
 */
@Composable
internal fun ClashDialog(
    clash: Clashing,
    folder: String,
    moving: Boolean,
    rest: Int,
    onChoose: (Clash, forRest: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var forRest by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.clash_title, clash.here.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Version(stringResource(R.string.clash_here, folder), clash.here)
                Version(
                    stringResource(
                        if (moving) R.string.clash_incoming_move else R.string.clash_incoming_copy
                    ),
                    clash.incoming,
                )
                if (rest > 0) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .toggleable(
                                    value = forRest,
                                    role = Role.Checkbox,
                                    onValueChange = { forRest = it },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = forRest, onCheckedChange = null)
                        Text(
                            text = pluralStringResource(R.plurals.clash_rest, rest, rest),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onChoose(Clash.Skip, forRest) }) {
                    Text(stringResource(R.string.skip))
                }
                TextButton(onClick = { onChoose(Clash.Replace, forRest) }) {
                    Text(stringResource(R.string.replace))
                }
                TextButton(onClick = { onChoose(Clash.KeepBoth, forRest) }) {
                    Text(stringResource(R.string.keep_both))
                }
            }
        },
    )
}

@Composable
private fun Version(label: String, entry: Entry, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = Human.meta(entry), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Size, where it lives and when it changed, with the full path for when it's needed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsSheet(entry: Entry, folder: String, address: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = entry.name, style = MaterialTheme.typography.headlineSmall)
            Fact(
                stringResource(R.string.details_size),
                if (entry.isFolder) stringResource(R.string.details_kind_folder)
                else Human.size(entry.size),
            )
            Fact(stringResource(R.string.details_where), folder)
            if (entry.modified > 0) {
                Fact(
                    stringResource(R.string.details_changed),
                    Human.date(entry.modified, withTime = true),
                )
            }
            Fact(stringResource(R.string.details_path), address + entry.path, monospace = true)
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}
