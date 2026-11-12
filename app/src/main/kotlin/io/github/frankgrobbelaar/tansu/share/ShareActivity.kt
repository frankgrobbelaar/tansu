package io.github.frankgrobbelaar.tansu.share

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.files.SharedFiles
import io.github.frankgrobbelaar.tansu.ui.ChosenTheme
import io.github.frankgrobbelaar.tansu.ui.rememberNotificationAsk
import io.github.frankgrobbelaar.tansu.ui.share.SaveToActions
import io.github.frankgrobbelaar.tansu.ui.share.SaveToScreen
import io.github.frankgrobbelaar.tansu.ui.share.SaveToViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tansu in Android's share sheet: Save to for any number of files from any app.
 *
 * Android lets tansu read what was shared only while this activity is alive, so once Save here
 * starts the job it steps back rather than finishing, and finishes when the job is done. It runs in
 * a task of its own, out of Recents, so stepping back returns you to the app you shared from.
 */
class ShareActivity : ComponentActivity() {

    // The app's own resolver: the job outlives this screen, and mustn't hold on to it.
    private val shared by lazy { SharedFiles(applicationContext.contentResolver) }
    private val uris by lazy { sharedUris(intent) }
    private val viewModel: SaveToViewModel by viewModels {
        SaveToViewModel.factory(shared, read = { shared.entries(uris) }, from = sourceApp())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (uris.isEmpty()) {
            finish()
            return
        }
        lifecycle.addObserver((application as TansuApplication).container.front)
        setContent {
            ChosenTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val askNotifications = rememberNotificationAsk()
                LaunchedEffect(state.savedAs) { state.savedAs?.let(::stepBack) }
                SaveToScreen(
                    state = state,
                    actions =
                        SaveToActions(
                            onClose = ::finish,
                            onSelectDrawer = viewModel::select,
                            onOpen = viewModel::open,
                            onCrumb = viewModel::jumpTo,
                            onNewFolder = viewModel::createFolder,
                            onSave = { askNotifications(viewModel::save) },
                        ),
                )
            }
        }
    }

    /** Back to the app the files came from, staying alive in the background until [job] ends. */
    private fun stepBack(job: Long) {
        moveTaskToBack(true)
        val jobs = (application as TansuApplication).container.jobs
        lifecycleScope.launch {
            jobs.all.first { all -> all.find { it.id == job }?.active != true }
            finishAndRemoveTask()
        }
    }

    /** The app that shared, by name, when Android says which it was. */
    private fun sourceApp(): String {
        val name =
            referrer
                ?.takeIf { it.scheme == "android-app" }
                ?.host
                ?.let { app ->
                    try {
                        packageManager
                            .getApplicationLabel(packageManager.getApplicationInfo(app, 0))
                            .toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
        return name ?: getString(R.string.save_to_another_app)
    }
}

/** Every file in a share: one, or many, as Android passes them. */
private fun sharedUris(intent: Intent): List<Uri> {
    val streams =
        when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    // Some apps only fill in the clip.
    val clip =
        intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
    return (streams + clip.orEmpty()).distinct()
}
