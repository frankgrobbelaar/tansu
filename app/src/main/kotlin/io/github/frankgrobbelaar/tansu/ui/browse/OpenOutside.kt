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

/** Offers files on the phone to any app that takes them, through Android's share sheet. */
fun shareOutside(context: Context, entries: List<Entry>, chooserTitle: String) {
    val uris = entries.map {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(it.path))
    }
    val types = entries.map { typeOf(File(it.path)) }.distinct()
    val share =
        if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    share.type = types.singleOrNull() ?: "*/*"
    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(share, chooserTitle))
}

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
