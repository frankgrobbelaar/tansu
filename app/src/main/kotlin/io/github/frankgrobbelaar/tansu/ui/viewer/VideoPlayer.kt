package io.github.frankgrobbelaar.tansu.ui.viewer

import android.net.Uri
import android.util.Pair
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Plays one video, streamed from its drawer as it plays. Holding the player here survives turning
 * the phone.
 */
@OptIn(UnstableApi::class)
class VideoViewModel(app: TansuApplication, drawers: StateFlow<List<Drawer>>, key: Viewer.Video) :
    ViewModel() {
    val player: ExoPlayer =
        ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DrawerDataSource.Factory(drawers)))
            .setSeekBackIncrementMs(SEEK_MILLIS)
            .setSeekForwardIncrementMs(SEEK_MILLIS)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(DrawerDataSource.uriOf(key.drawerId, key.path)))
                prepare()
                playWhenReady = true
            }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private const val SEEK_MILLIS = 10_000L

        fun factory(key: Viewer.Video): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as TansuApplication
                VideoViewModel(app, app.container.drawers.all, key)
            }
        }
    }
}

/** A video on a dark backdrop, with the controls you'd expect and nothing more. */
@OptIn(UnstableApi::class)
@Composable
fun VideoRoute(
    key: Viewer.Video,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel = viewModel(factory = VideoViewModel.factory(key)),
) {
    val player = viewModel.player
    DarkBars()
    // Leaving tansu pauses it.
    LifecycleStartEffect(player) { onStopOrDispose { player.pause() } }
    var chrome by remember { mutableStateOf(true) }
    Box(modifier = modifier.fillMaxSize().background(Backdrop)) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setErrorMessageProvider {
                        Pair.create(0, context.getString(R.string.video_unplayable))
                    }
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { chrome = it == View.VISIBLE }
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(name = key.name, label = key.label, onBack = onBack)
        }
    }
}

/**
 * Media3 reading a drawer's file through its file system: the phone, SMB or SFTP. Each open starts
 * at the position asked for, so seeking doesn't read what's skipped.
 */
@UnstableApi
internal class DrawerDataSource(private val drawers: StateFlow<List<Drawer>>) :
    BaseDataSource(/* isNetwork= */ true) {
    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var left = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val files = drawers.value.find { it.id == dataSpec.uri.host }?.fileSystem
        val path = dataSpec.uri.path.orEmpty()
        // Media3 calls this on its own loading thread, where waiting is fine.
        val (input, size) =
            runBlocking { files?.let { openAt(it, path, dataSpec.position) } }
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        stream = input
        left =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else size - dataSpec.position
        transferStarted(dataSpec)
        return left
    }

    /** The file read from [position], and its size; null if it isn't there. */
    private suspend fun openAt(files: FileSystem, path: String, position: Long) =
        try {
            files.stat(path)?.let { entry -> files.read(path, position) to entry.size }
        } catch (e: FileSystemException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val read = checkNotNull(stream).read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (read < 0) return C.RESULT_END_OF_INPUT
        left -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val open = stream
        stream = null
        uri = null
        if (open != null) {
            try {
                open.close()
            } finally {
                transferEnded()
            }
        }
    }

    class Factory(private val drawers: StateFlow<List<Drawer>>) : DataSource.Factory {
        override fun createDataSource(): DataSource = DrawerDataSource(drawers)
    }

    companion object {
        /** A drawer's file as Media3 names it: tansu://drawer/path. */
        fun uriOf(drawerId: String, path: String): Uri =
            Uri.Builder().scheme("tansu").authority(drawerId).path(path).build()
    }
}
