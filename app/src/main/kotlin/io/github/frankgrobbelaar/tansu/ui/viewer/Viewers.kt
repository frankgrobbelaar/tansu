package io.github.frankgrobbelaar.tansu.ui.viewer

import androidx.navigation3.runtime.NavKey
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileKind
import kotlinx.serialization.Serializable

/** A file open in tansu itself: a picture, a video or a PDF. Each is a screen of its own. */
@Serializable
sealed interface Viewer : NavKey {
    /** The pictures in [folder] of the drawer [drawerId], starting at [path]. */
    @Serializable
    data class Picture(val drawerId: String, val folder: String, val path: String) : Viewer

    /** The video at [path] in the drawer [drawerId], streamed as it plays. */
    @Serializable
    data class Video(
        val drawerId: String,
        val path: String,
        val name: String,
        /** Where it is, as a person reads it, such as "NAS › Media". */
        val label: String,
    ) : Viewer

    /** The PDF at [file] on the phone; a server's is fetched to the phone first. */
    @Serializable data class Pdf(val file: String, val name: String, val label: String) : Viewer
}

/** What tansu shows itself; anything else opens in another app. */
enum class Viewing {
    Picture,
    Video,
    Pdf,
}

/** How [entry] opens in tansu, or null when another app should open it. */
fun viewingOf(entry: Entry): Viewing? =
    when {
        entry.isFolder -> null
        FileKind.of(entry) == FileKind.Image -> Viewing.Picture
        FileKind.of(entry) == FileKind.Video -> Viewing.Video
        entry.name.endsWith(".pdf", ignoreCase = true) -> Viewing.Pdf
        else -> null
    }
