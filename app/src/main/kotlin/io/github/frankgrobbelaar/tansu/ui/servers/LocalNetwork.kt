package io.github.frankgrobbelaar.tansu.ui.servers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Android 17 keeps apps off the local network until the user allows it, so a NAS at home needs the
 * permission; servers reached through Tailscale don't. Earlier Android versions don't ask.
 */
object LocalNetwork {
    // Written out: the constant is new in Android 17 and would be inlined into older builds.
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
}

/** Whether tansu may use the local network, and how to ask for it. */
class LocalNetworkAccess(val allowed: Boolean, val ask: (then: () -> Unit) -> Unit)

/**
 * Local network access, checked again whenever tansu comes back to the front.
 * [LocalNetworkAccess.ask] asks Android once and then carries on either way: a server on Tailscale
 * works without it. Once Android has stopped asking, it opens tansu's settings instead.
 */
@Composable
fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var allowed by remember { mutableStateOf(LocalNetwork.allowed(context)) }
    var then by remember { mutableStateOf({}) }
    val next by rememberUpdatedState(then)
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = granted
            val stillAsking =
                activity?.shouldShowRequestPermissionRationale(LocalNetwork.PERMISSION) == true
            if (!granted && !stillAsking) context.startActivity(appSettings(context))
            next()
        }
    LifecycleResumeEffect(Unit) {
        allowed = LocalNetwork.allowed(context)
        onPauseOrDispose {}
    }
    return LocalNetworkAccess(allowed) { action ->
        if (LocalNetwork.allowed(context)) {
            action()
        } else {
            then = action
            request.launch(LocalNetwork.PERMISSION)
        }
    }
}

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
