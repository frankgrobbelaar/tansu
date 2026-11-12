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

    /**
     * Whether [host] is on the home network as far as its name says: a private or link-local
     * address, a name ending in .local or .lan, or a bare name. Tailscale's 100.64/10 addresses and
     * .ts.net names aren't; Android lets those through.
     */
    fun looksLocal(host: String): Boolean {
        val name = host.trim().trimStart('[').trimEnd(']').lowercase()
        val numbers = name.split('.').mapNotNull { it.toIntOrNull() }
        return when {
            numbers.size == IPV4_PARTS && name.count { it == '.' } == IPV4_PARTS - 1 ->
                isPrivate(numbers[0], numbers[1])
            ':' in name -> name.startsWith("fe80") || name.startsWith("fc") || name.startsWith("fd")
            name.endsWith(".ts.net") -> false
            else -> '.' !in name || LOCAL_ENDINGS.any { name.endsWith(it) }
        }
    }

    private fun isPrivate(first: Int, second: Int) =
        first == PRIVATE_A ||
            (first == PRIVATE_B && second in PRIVATE_B_SECOND) ||
            (first == PRIVATE_C && second == PRIVATE_C_SECOND) ||
            (first == LINK_LOCAL && second == LINK_LOCAL_SECOND)

    private const val IPV4_PARTS = 4
    private const val PRIVATE_A = 10
    private const val PRIVATE_B = 172
    private val PRIVATE_B_SECOND = 16..31
    private const val PRIVATE_C = 192
    private const val PRIVATE_C_SECOND = 168
    private const val LINK_LOCAL = 169
    private const val LINK_LOCAL_SECOND = 254
    private val LOCAL_ENDINGS = listOf(".local", ".lan", ".home", ".internal")
}

/**
 * Whether tansu may use the local network, and how to ask. [ask] asks Android and then carries on
 * either way: a server on Tailscale works without it. [allow] is for an Allow button: once Android
 * has stopped asking, it opens tansu's settings instead.
 */
class LocalNetworkAccess(
    val allowed: Boolean,
    val ask: (then: () -> Unit) -> Unit,
    val allow: (then: () -> Unit) -> Unit,
)

/** Local network access, checked again whenever tansu comes back to the front. */
@Composable
fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var allowed by remember { mutableStateOf(LocalNetwork.allowed(context)) }
    var then by remember { mutableStateOf({}) }
    var settingsIfRefused by remember { mutableStateOf(false) }
    val next by rememberUpdatedState(then)
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = granted
            val stillAsking =
                activity?.shouldShowRequestPermissionRationale(LocalNetwork.PERMISSION) == true
            if (!granted && !stillAsking && settingsIfRefused) {
                context.startActivity(appSettings(context))
            }
            next()
        }
    LifecycleResumeEffect(Unit) {
        allowed = LocalNetwork.allowed(context)
        onPauseOrDispose {}
    }
    val asking = { action: () -> Unit, orSettings: Boolean ->
        if (LocalNetwork.allowed(context)) {
            action()
        } else {
            then = action
            settingsIfRefused = orSettings
            request.launch(LocalNetwork.PERMISSION)
        }
    }
    return LocalNetworkAccess(
        allowed = allowed,
        ask = { asking(it, false) },
        allow = { asking(it, true) },
    )
}

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
