package io.github.frankgrobbelaar.tansu.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for notifications the first time a job starts, so its progress can show outside tansu, then
 * starts it either way: a job runs without them too.
 */
@Composable
fun rememberNotificationAsk(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var then by remember { mutableStateOf({}) }
    val next by rememberUpdatedState(then)
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    return { action ->
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            then = action
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
