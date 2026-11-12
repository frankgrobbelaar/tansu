package io.github.frankgrobbelaar.tansu.ui.browse

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/** Hands a file on the phone to the app Android picks for it. False when no app takes it. */
fun openOutside(context: Context, file: File): Boolean {
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(context, file), typeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Offers files on the phone to any app that takes them, through Android's share sheet. */
fun shareOutside(context: Context, files: List<File>, chooserTitle: String) {
    if (files.isEmpty()) return
    val uris = files.map { uriOf(context, it) }
    val share =
        if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    share.type = files.map(::typeOf).distinct().singleOrNull() ?: "*/*"
    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(share, chooserTitle))
}

private fun uriOf(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun typeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

/**
 * Opens Android's screen for giving tansu All files access: tansu's own page, or the list of apps
 * on phones that don't have one.
 */
fun openAllFilesAccess(context: Context) {
    val ownPage =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
    try {
        context.startActivity(ownPage)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
