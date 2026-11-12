package io.github.frankgrobbelaar.tansu.files

import io.github.frankgrobbelaar.tansu.servers.Server

/** A place files live, shown as a chip at the top of Browse: the phone, or a server. */
data class Drawer(
    val id: String,
    val name: String,
    val kind: Kind,
    /** Where Browse starts in this drawer, and the highest it goes. */
    val root: String,
    val fileSystem: FileSystem,
    /** The server behind this drawer; none for the phone. */
    val server: Server? = null,
) {
    enum class Kind {
        Phone,
        Smb,
        Sftp,
    }
}
