package io.github.frankgrobbelaar.tansu.ui.servers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A private key file picked on the phone: its name, to show, and what it says. */
class KeyFile(val name: String, val text: String)

/** Reads the key file at [uri], or nothing if it can't be read or is too big to be a key. */
suspend fun readKeyFile(context: Context, uri: Uri): KeyFile? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
        try {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(MAX_KEY_BYTES + 1)
                if (bytes.size > MAX_KEY_BYTES) null else KeyFile(name, bytes.decodeToString())
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

/** Even a 16384-bit RSA key in PuTTY's format is well under this. */
private const val MAX_KEY_BYTES = 64 * 1024
