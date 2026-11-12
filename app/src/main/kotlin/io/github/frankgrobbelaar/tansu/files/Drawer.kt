package io.github.frankgrobbelaar.tansu.files

/** A place files live, shown as a chip at the top of Browse: the phone, or a server. */
data class Drawer(
    val id: String,
    val name: String,
    val kind: Kind,
    /** Where Browse starts in this drawer, and the highest it goes. */
    val root: String,
    val fileSystem: FileSystem,
) {
    enum class Kind {
        Phone,
        Smb,
        Sftp,
    }
}
