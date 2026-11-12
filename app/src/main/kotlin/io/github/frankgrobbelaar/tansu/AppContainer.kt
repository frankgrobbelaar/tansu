package io.github.frankgrobbelaar.tansu

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Drawers
import io.github.frankgrobbelaar.tansu.files.Fetcher
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.LocalFileSystem
import io.github.frankgrobbelaar.tansu.files.SftpFileSystem
import io.github.frankgrobbelaar.tansu.files.SmbFileSystem
import io.github.frankgrobbelaar.tansu.jobs.Clipboard
import io.github.frankgrobbelaar.tansu.jobs.Jobs
import io.github.frankgrobbelaar.tansu.jobs.TransferService
import io.github.frankgrobbelaar.tansu.servers.LiveServerCheck
import io.github.frankgrobbelaar.tansu.servers.Secrets
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.servers.ServerCheck
import io.github.frankgrobbelaar.tansu.servers.ServerStore
import io.github.frankgrobbelaar.tansu.servers.Servers
import io.github.frankgrobbelaar.tansu.servers.StoredSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Whether Android has given tansu All files access. */
fun interface StorageAccess {
    fun granted(): Boolean
}

/**
 * Everything the app shares, made once and wired by hand. Tests pass their own [storageAccess],
 * [phoneRoot] and [secrets] instead of Android's.
 */
class AppContainer(
    context: Context,
    val storageAccess: StorageAccess = StorageAccess { Environment.isExternalStorageManager() },
    phoneRoot: String = primaryStorage(context),
    secrets: Secrets = StoredSecrets(store(context, "secrets")),
) {
    /** Work that outlives any one screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = TansuSettings(store(context, "settings"))

    val servers = Servers(ServerStore(store(context, "servers")), secrets)

    val phone =
        Drawer(
            id = PHONE,
            name = context.getString(R.string.drawer_phone),
            kind = Drawer.Kind.Phone,
            root = phoneRoot,
            fileSystem = LocalFileSystem(),
        )

    val drawers = Drawers(phone, servers.all, { fileSystemFor(it, secrets) }, scope)

    val serverCheck: ServerCheck = LiveServerCheck(::fileSystemFor)

    val jobs = Jobs(drawers.all, scope, onBusy = { TransferService.start(context) })

    val clipboard = Clipboard()

    val fetcher = Fetcher(context.cacheDir)

    companion object {
        const val PHONE = "phone"
    }
}

private fun fileSystemFor(server: Server, secrets: Secrets): FileSystem =
    when (server.kind) {
        Server.Kind.Smb -> SmbFileSystem(server, secrets)
        Server.Kind.Sftp -> SftpFileSystem(server, secrets)
    }

private fun store(context: Context, name: String) = PreferenceDataStoreFactory.create {
    context.preferencesDataStoreFile(name)
}

/** The top of the phone's shared storage, where Browse starts on the phone. */
private fun primaryStorage(context: Context): String =
    context
        .getSystemService(StorageManager::class.java)
        ?.storageVolumes
        ?.firstOrNull { it.isPrimary }
        ?.directory
        ?.path ?: "/storage/emulated/0"

/** The app's container, from anywhere in the UI. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as TansuApplication).container
