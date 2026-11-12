package io.github.frankgrobbelaar.tansu.ui.viewer

import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.frankgrobbelaar.tansu.R
import java.io.File
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A PDF's pages, once it's open, or that it can't be shown. */
data class PdfUiState(val pages: Int = 0, val loading: Boolean = true, val failed: Boolean = false)

/** Opens a PDF on the phone with Android's renderer, and draws its pages as they're needed. */
class PdfViewModel(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(PdfUiState())
    val state: StateFlow<PdfUiState> = _state.asStateFlow()

    // The renderer draws one page at a time, and can't close while it draws: all under one lock.
    private val drawing = Mutex()
    private var pdf: PdfRenderer? = null
    @Volatile private var cleared = false

    init {
        viewModelScope.launch {
            val opened = withContext(io + NonCancellable) { open() }
            drawing.withLock {
                // Gone before it opened: it's closed rather than kept.
                if (cleared) opened?.close() else pdf = opened
            }
            _state.value =
                PdfUiState(pages = opened?.pageCount ?: 0, loading = false, failed = opened == null)
        }
    }

    private fun open(): PdfRenderer? =
        try {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            // Locked with a password.
            null
        }

    /** Page [index], drawn [width] pixels wide on white, no bigger than a phone can hold. */
    suspend fun page(index: Int, width: Int): ImageBitmap? =
        withContext(io) {
            drawing.withLock {
                try {
                    if (cleared || width <= 0) null else draw(index, width)
                } finally {
                    if (cleared) close()
                }
            }
        }

    private fun draw(index: Int, width: Int): ImageBitmap? =
        pdf?.openPage(index)?.use { page ->
            if (page.width <= 0 || page.height <= 0) return@use null
            // A very tall page is drawn narrower rather than run out of memory.
            val scale =
                minOf(1.0, sqrt(MAX_PIXELS / (width.toDouble() * width * page.height / page.width)))
            val wide = (width * scale).toInt().coerceAtLeast(1)
            val high = (wide.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(wide, high)
            bitmap.eraseColor(AndroidColor.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.asImageBitmap()
        }

    override fun onCleared() {
        cleared = true
        // Closing while a page is drawn throws: then the drawing closes it when it's done.
        if (drawing.tryLock()) {
            try {
                close()
            } finally {
                drawing.unlock()
            }
        }
    }

    /** The renderer closes the file with it. */
    private fun close() {
        pdf?.close()
        pdf = null
    }

    companion object {
        fun factory(file: File): ViewModelProvider.Factory = viewModelFactory {
            initializer { PdfViewModel(file) }
        }
    }
}

@Composable
fun PdfRoute(
    key: Viewer.Pdf,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PdfViewModel = viewModel(factory = PdfViewModel.factory(File(key.file))),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DarkBars()
    Box(modifier = modifier.fillMaxSize().background(Backdrop)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.failed ->
                Text(
                    text = stringResource(R.string.pdf_unreadable),
                    color = Light,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            else -> Pages(state.pages, viewModel::page, key, onBack)
        }
    }
}

/** Every page, one under the other, with the page you're on in the top bar. */
@Composable
private fun Pages(
    count: Int,
    draw: suspend (Int, Int) -> ImageBitmap?,
    key: Viewer.Pdf,
    onBack: () -> Unit,
) {
    val list = rememberLazyListState()
    // Read as it scrolls, not on every frame of it.
    val showing by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        LazyColumn(
            state = list,
            modifier =
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    ),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 96.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(count) { index -> Page(index, width, draw) }
        }
        ViewerTopBar(
            name = key.name,
            label = key.label,
            onBack = onBack,
            actions = {
                Text(
                    text = stringResource(R.string.pdf_page, showing, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = Light,
                    modifier = Modifier.padding(end = 16.dp),
                )
            },
        )
    }
}

@Composable
private fun Page(index: Int, width: Int, draw: suspend (Int, Int) -> ImageBitmap?) {
    val drawing by rememberUpdatedState(draw)
    val page by produceState<ImageBitmap?>(null, index, width) { value = drawing(index, width) }
    val drawn = page
    val shape = RoundedCornerShape(4.dp)
    if (drawn == null) {
        // A page-shaped space until it's drawn.
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(A4).clip(shape).background(Color.White))
    } else {
        Image(
            bitmap = drawn,
            contentDescription = stringResource(R.string.pdf_page_label, index + 1),
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(drawn.width.toFloat() / drawn.height)
                    .clip(shape),
        )
    }
}

/** An A4 page's width over its height. */
private const val A4 = 210f / 297f

/** The most pixels one page is drawn with: about 64 MB of bitmap. */
private const val MAX_PIXELS = 16_000_000.0
