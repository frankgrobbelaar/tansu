package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.jobs.Clash

/** Shows Browse's open [dialog], and passes on what it decides. */
@Composable
internal fun BrowseDialogHost(
    dialog: BrowseDialog?,
    state: BrowseUiState,
    decisions: DialogDecisions,
    onDialog: (BrowseDialog?) -> Unit,
) {
    val onClose = { onDialog(null) }
    when (dialog) {
        null -> Unit
        BrowseDialog.RemoveServer ->
            RemoveServerDialog(
                name = state.drawer.name,
                onRemove = {
                    onClose()
                    decisions.onRemoveServer()
                },
                onCancel = onClose,
            )
        BrowseDialog.NewFolder ->
            NameDialog(
                title = stringResource(R.string.new_folder),
                confirm = stringResource(R.string.create),
                onConfirm = { name ->
                    onClose()
                    decisions.onCreateFolder(name)
                },
                onCancel = onClose,
            )
        BrowseDialog.Delete ->
            DeleteDialog(
                picked = state.picked,
                drawer = state.drawer.name,
                folder = state.label,
                onDelete = {
                    onClose()
                    decisions.onDelete()
                },
                onCancel = onClose,
            )
        is BrowseDialog.Rename ->
            RenameDialog(
                entry = dialog.entry,
                onRename = { name ->
                    onClose()
                    decisions.onRename(dialog.entry, name)
                },
                onCancel = onClose,
            )
        is BrowseDialog.Details ->
            DetailsSheet(
                entry = dialog.entry,
                folder = state.label,
                address = state.address,
                onDismiss = onClose,
            )
        is BrowseDialog.Clashes ->
            // A fresh dialog for each clash, so its "do this for the rest" box starts unticked.
            key(dialog.left.first().incoming.path) {
                ClashSteps(dialog, state, decisions.onPaste, onDialog)
            }
    }
}

/** One clash at a time, until each is settled or one answer settles the rest; then the paste. */
@Composable
private fun ClashSteps(
    dialog: BrowseDialog.Clashes,
    state: BrowseUiState,
    onPaste: (Map<String, Clash>) -> Unit,
    onDialog: (BrowseDialog?) -> Unit,
) {
    val clash = dialog.left.first()
    ClashDialog(
        clash = clash,
        folder = state.title,
        moving = state.held?.cut == true,
        rest = dialog.left.size - 1,
        onChoose = { choice: Clash, forRest ->
            val settled = if (forRest) dialog.left else listOf(clash)
            val choices = dialog.choices + settled.associate { it.incoming.name to choice }
            val left = dialog.left - settled.toSet()
            if (left.isEmpty()) {
                onDialog(null)
                onPaste(choices)
            } else {
                onDialog(BrowseDialog.Clashes(left, choices))
            }
        },
        onCancel = { onDialog(null) },
    )
}

/** A new name for [entry]; a file keeps its extension. */
@Composable
private fun RenameDialog(entry: Entry, onRename: (String) -> Unit, onCancel: () -> Unit) {
    val dot = if (entry.isFolder) -1 else entry.name.lastIndexOf('.')
    NameDialog(
        title = stringResource(R.string.rename_title),
        confirm = stringResource(R.string.rename),
        initial = if (dot > 0) entry.name.take(dot) else entry.name,
        extension = if (dot > 0) entry.name.drop(dot) else "",
        onConfirm = onRename,
        onCancel = onCancel,
    )
}
