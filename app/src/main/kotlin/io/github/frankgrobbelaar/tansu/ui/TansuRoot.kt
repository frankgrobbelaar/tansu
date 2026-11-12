package io.github.frankgrobbelaar.tansu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.frankgrobbelaar.tansu.appContainer
import io.github.frankgrobbelaar.tansu.ui.browse.openAllFilesAccess
import io.github.frankgrobbelaar.tansu.ui.firstrun.FirstRunScreen
import kotlinx.coroutines.launch

/**
 * The first run's two screens until tansu has All files access or you choose to go on without it;
 * the app from then on.
 */
@Composable
fun TansuRoot(modifier: Modifier = Modifier) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val welcomeDone by
        container.settings.welcomeDone.collectAsStateWithLifecycle(initialValue = null)
    var hasAccess by remember { mutableStateOf(container.storageAccess.granted()) }

    // Android's settings screen is another app: look again whenever tansu comes back.
    LifecycleResumeEffect(container) {
        hasAccess = container.storageAccess.granted()
        onPauseOrDispose {}
    }
    LaunchedEffect(hasAccess, welcomeDone) {
        if (hasAccess && welcomeDone == false) container.settings.finishWelcome()
    }

    when {
        welcomeDone == null -> Unit
        welcomeDone == false && !hasAccess ->
            FirstRunScreen(
                onOpenSettings = { openAllFilesAccess(context) },
                onNotNow = { scope.launch { container.settings.finishWelcome() } },
                modifier = modifier,
            )
        else -> TansuApp(modifier)
    }
}
