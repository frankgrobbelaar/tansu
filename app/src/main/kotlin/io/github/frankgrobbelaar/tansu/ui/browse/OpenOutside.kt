package io.github.frankgrobbelaar.tansu.ui.browse

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.frankgrobbelaar.tansu.files.Entry
import java.io.File

/** Hands a file on the phone to the app Android picks for it. False when no app takes it. */
fun openOutside(context: Context, entry: Entry): Boolean {
    val file = File(entry.path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val type =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Android's own screen for giving tansu All files access. */
fun allFilesAccessIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${context.packageName}".toUri(),
    )
