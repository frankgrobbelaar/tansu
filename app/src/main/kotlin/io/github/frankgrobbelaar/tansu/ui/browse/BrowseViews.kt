package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileKind
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.icon
import io.github.frankgrobbelaar.tansu.ui.viewer.DrawerImage

/** The top bar while searching: what to look for, Clear, and back to the folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(
    folder: String,
    onSearch: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val search by rememberUpdatedState(onSearch)
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { search(it) } }
    LaunchedEffect(focus) { focus.requestFocus() }
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = { onSearch(null) }) {
                Icon(TansuIcons.Back, contentDescription = stringResource(R.string.close_search))
            }
        },
        title = {
            TextField(
                state = text,
                placeholder = { Text(stringResource(R.string.search_folder, folder)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (text.text.isNotEmpty()) {
                IconButton(onClick = { text.clearText() }) {
                    Icon(
                        TansuIcons.Close,
                        contentDescription = stringResource(R.string.clear_search),
                    )
                }
            }
        },
    )
}

/** [name] with the first place it has [match] in bold, if it has it. */
internal fun marked(name: String, match: String?): AnnotatedString {
    if (match.isNullOrEmpty()) return AnnotatedString(name)
    val at = name.indexOf(match, ignoreCase = true)
    if (at < 0) return AnnotatedString(name)
    return buildAnnotatedString {
        append(name.take(at))
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
            append(name.substring(at, at + match.length))
        }
        append(name.drop(at + match.length))
    }
}

/** What a search is: of this folder, by name. */
@Composable
internal fun SearchNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.search_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** The folder as a grid, three across, where pictures matter. */
@Composable
internal fun EntryGrid(
    state: BrowseUiState,
    actions: BrowseActions,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.entries, key = { it.path }) { entry ->
            EntryTile(
                entry = entry,
                drawerId = state.drawer.id,
                name = marked(entry.name, state.search),
                onClick = { actions.onOpen(entry) },
                onLongClick = { actions.onPick(entry) },
                selected = entry.path in state.selected,
                modifier = Modifier.animateItem(),
            )
        }
        if (!state.search.isNullOrEmpty()) {
            item(key = "search note", span = { GridItemSpan(maxLineSpan) }) { SearchNote() }
        }
    }
}

@Composable
private fun EntryTile(
    entry: Entry,
    drawerId: String,
    name: AnnotatedString,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val kind = FileKind.of(entry)
    Column(
        modifier =
            modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.select),
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            selected -> colors.primary.copy(alpha = 0.24f)
                            kind == FileKind.Folder -> colors.primaryContainer
                            else -> colors.surfaceContainer
                        }
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = kind.icon,
                contentDescription = null,
                tint = if (kind == FileKind.Folder) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            // Pictures show themselves, on the phone and on servers; the icon waits underneath.
            if (kind == FileKind.Image) {
                AsyncImage(
                    model = DrawerImage(drawerId, entry),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Icon(
                    imageVector = TansuIcons.Check,
                    contentDescription = stringResource(R.string.row_selected),
                    tint = colors.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp),
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

private const val GRID_COLUMNS = 3
