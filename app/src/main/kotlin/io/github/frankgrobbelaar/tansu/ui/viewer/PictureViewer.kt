package io.github.frankgrobbelaar.tansu.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.TansuSettings
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.browseOrder
import io.github.frankgrobbelaar.tansu.ui.format.Human
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pictures in a folder, in Browse's order, and the one to start at. */
data class PicturesUiState(
    val pictures: List<Entry> = emptyList(),
    val start: Int = 0,
    /** Where they are, such as "NAS › Media › Photos". */
    val label: String = "",
    val loading: Boolean = true,
    /** The folder couldn't be listed. */
    val failed: Boolean = false,
)

/** Lists a folder's pictures once, so they can be swiped through. */
class PicturesViewModel(
    drawers: StateFlow<List<Drawer>>,
    settings: TansuSettings,
    private val key: Viewer.Picture,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(PicturesUiState())
    val state: StateFlow<PicturesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val drawer = drawers.value.find { it.id == key.drawerId }
            _state.value =
                if (drawer == null) {
                    PicturesUiState(loading = false, failed = true)
                } else {
                    picturesIn(drawer, settings)
                }
        }
    }

    private suspend fun picturesIn(drawer: Drawer, settings: TansuSettings): PicturesUiState {
        val label = labelOf(drawer, key.folder)
        val choices = settings.choices.first()
        return try {
            val listed = drawer.fileSystem.list(key.folder)
            val pictures =
                withContext(compute) {
                    listed
                        .filter { viewingOf(it) == Viewing.Picture }
                        .filter { choices.showHidden || !it.isHidden }
                        .sortedWith(browseOrder(choices.sort))
                }
            val start = pictures.indexOfFirst { it.path == key.path }.coerceAtLeast(0)
            PicturesUiState(pictures, start, label, loading = false)
        } catch (_: FileSystemException) {
            PicturesUiState(label = label, loading = false, failed = true)
        }
    }

    companion object {
        fun factory(key: Viewer.Picture): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as TansuApplication).container
                PicturesViewModel(container.drawers.all, container.settings, key)
            }
        }
    }
}

/** A folder as a person reads it: the drawer, then each folder down from its top. */
internal fun labelOf(drawer: Drawer, folder: String): String =
    (listOf(drawer.name) + folder.removePrefix(drawer.root).split('/').filter { it.isNotEmpty() })
        .joinToString(" › ")

@Composable
fun PictureRoute(
    key: Viewer.Picture,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PicturesViewModel = viewModel(factory = PicturesViewModel.factory(key)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PictureScreen(state = state, drawerId = key.drawerId, onBack = onBack, modifier = modifier)
}

/** Full screen on a dark backdrop: swipe for the next, pinch or double-tap to zoom. */
@Composable
fun PictureScreen(
    state: PicturesUiState,
    drawerId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DarkBars()
    var chrome by rememberSaveable { mutableStateOf(true) }
    Box(modifier = modifier.fillMaxSize().background(Backdrop)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.failed || state.pictures.isEmpty() ->
                Text(
                    text = stringResource(R.string.viewer_unreadable),
                    color = Light,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            else -> {
                val pager =
                    rememberPagerState(initialPage = state.start.coerceIn(state.pictures.indices)) {
                        state.pictures.size
                    }
                var zoomed by remember { mutableStateOf(false) }
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !zoomed,
                    key = { state.pictures[it].path },
                ) { page ->
                    ZoomablePicture(
                        image = DrawerImage(drawerId, state.pictures[page]),
                        onZoom = { zoomed = it },
                        onTap = { chrome = !chrome },
                    )
                }
                val picture = state.pictures[pager.currentPage.coerceIn(state.pictures.indices)]
                Chrome(visible = chrome, modifier = Modifier.align(Alignment.TopCenter)) {
                    ViewerTopBar(name = picture.name, label = state.label, onBack = onBack)
                }
                Chrome(visible = chrome, modifier = Modifier.align(Alignment.BottomCenter)) {
                    PictureFoot(picture, place = pager.currentPage + 1, of = state.pictures.size)
                }
            }
        }
    }
}

@Composable
private fun Chrome(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        content()
    }
}

/** Which picture this is, its size and when it was taken or last changed. */
@Composable
private fun PictureFoot(picture: Entry, place: Int, of: Int, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, FootShade)))
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.viewer_place, place, of),
            style = MaterialTheme.typography.titleSmall,
            color = Light,
        )
        Text(
            text = Human.size(picture.size) + " · " + Human.date(picture.modified, withTime = true),
            style = MaterialTheme.typography.labelMedium,
            color = Light.copy(alpha = 0.72f),
        )
    }
}

/** How far a picture is zoomed and panned, never past its edges. */
@Stable
private class Zoom {
    var scale by mutableFloatStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    var size = IntSize.Zero

    val zoomed: Boolean
        get() = scale > 1f

    /** In on the point tapped, or back out. */
    fun toggle(at: Offset) {
        if (zoomed) {
            scale = 1f
            offset = Offset.Zero
        } else {
            scale = DOUBLE_TAP_ZOOM
            val middle = Offset(size.width / 2f, size.height / 2f)
            offset = inBounds((middle - at) * (scale - 1))
        }
    }

    fun change(zoomBy: Float, panBy: Offset) {
        scale = (scale * zoomBy).coerceIn(1f, MAX_ZOOM)
        offset = if (zoomed) inBounds(offset + panBy) else Offset.Zero
    }

    private fun inBounds(wanted: Offset): Offset {
        val x = size.width * (scale - 1) / 2
        val y = size.height * (scale - 1) / 2
        return Offset(wanted.x.coerceIn(-x, x), wanted.y.coerceIn(-y, y))
    }
}

/**
 * One picture that zooms with two fingers or a double tap, and pans while zoomed. At its own size
 * it leaves one-finger swipes to the pager.
 */
@Composable
private fun ZoomablePicture(
    image: DrawerImage,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoom = remember { Zoom() }
    val zoomed by rememberUpdatedState(onZoom)
    val tapped by rememberUpdatedState(onTap)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { zoom.size = it }
                .pointerInput(zoom) {
                    detectTapGestures(
                        onTap = { tapped() },
                        onDoubleTap = { at ->
                            zoom.toggle(at)
                            zoomed(zoom.zoomed)
                        },
                    )
                }
                .pointerInput(zoom) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1 || zoom.zoomed) {
                                zoom.change(event.calculateZoom(), event.calculatePan())
                                zoomed(zoom.zoomed)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
    ) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
        )
    }
}

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 5f
private val FootShade = Color(0x99000000)
