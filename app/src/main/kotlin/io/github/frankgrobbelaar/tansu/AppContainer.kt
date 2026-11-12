package io.github.frankgrobbelaar.tansu

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.LocalFileSystem

/** Whether Android has given tansu All files access. */
fun interface StorageAccess {
    fun granted(): Boolean
}

/**
 * Everything the app shares, made once and wired by hand. Tests pass their own [storageAccess] and
 * [phoneRoot] instead of Android's.
 */
class AppContainer(
    context: Context,
    val storageAccess: StorageAccess = StorageAccess { Environment.isExternalStorageManager() },
    phoneRoot: String = primaryStorage(context),
) {
    val settings =
        TansuSettings(
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
        )

    val phone =
        Drawer(
            id = "phone",
            name = context.getString(R.string.drawer_phone),
            kind = Drawer.Kind.Phone,
            root = phoneRoot,
            fileSystem = LocalFileSystem(),
        )
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
