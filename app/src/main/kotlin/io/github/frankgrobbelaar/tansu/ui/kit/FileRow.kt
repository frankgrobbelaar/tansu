package io.github.frankgrobbelaar.tansu.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileKind
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/**
 * One file or folder in a list: its tile, its name, and the line under it. While selecting, a
 * [selected] row is shaded and every row shows whether it's in.
 */
@Composable
fun FileRow(
    entry: Entry,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    /** The name as shown, such as with what a search matched in bold. */
    name: AnnotatedString = AnnotatedString(entry.name),
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = SHADE)
                    else Color.Transparent
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    // TalkBack says what a long press does, and whether the row is picked.
                    onLongClickLabel = stringResource(R.string.select),
                )
                .semantics { if (selecting) this.selected = selected }
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FileTile(kind = FileKind.of(entry))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selecting) SelectMark(selected)
    }
}

/** A filled circle with a tick when selected; an empty ring when not. */
@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description =
        stringResource(if (selected) R.string.row_selected else R.string.row_not_selected)
    Box(
        modifier =
            modifier
                .size(22.dp)
                .semantics { contentDescription = description }
                .then(
                    if (selected) Modifier.background(colors.primary, CircleShape)
                    else Modifier.border(1.6.dp, colors.outline, CircleShape)
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = TansuIcons.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private const val SHADE = 0.6f

/** A folder sits on a wood tile; every other kind is a plain line icon. */
@Composable
fun FileTile(kind: FileKind, modifier: Modifier = Modifier) {
    val isFolder = kind == FileKind.Folder
    Box(
        modifier =
            modifier
                .size(42.dp)
                .clip(KiriCorners.Tile)
                .background(
                    if (isFolder) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint =
                if (isFolder) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

val FileKind.icon: ImageVector
    get() =
        when (this) {
            FileKind.Folder -> TansuIcons.Folder
            FileKind.Image -> TansuIcons.Picture
            FileKind.Video -> TansuIcons.Video
            FileKind.Audio -> TansuIcons.Audio
            FileKind.Archive -> TansuIcons.Archive
            FileKind.Document -> TansuIcons.Document
        }
