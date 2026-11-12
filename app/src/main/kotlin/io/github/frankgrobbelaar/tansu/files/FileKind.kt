package io.github.frankgrobbelaar.tansu.files

/** What sort of file a name suggests, for its icon and for what opening it does. */
enum class FileKind {
    Folder,
    Image,
    Video,
    Audio,
    Archive,
    Document;

    companion object {
        private val byExtension =
            mapOf(
                    Image to "jpg jpeg png gif webp heic heif bmp avif",
                    Video to "mp4 mkv mov webm avi m4v 3gp ts",
                    Audio to "mp3 m4a aac flac wav ogg opus wma",
                    Archive to "zip rar 7z tar gz tgz xz bz2 zst",
                )
                .flatMap { (kind, extensions) -> extensions.split(" ").map { it to kind } }
                .toMap()

        fun of(entry: Entry): FileKind =
            if (entry.isFolder) Folder
            else byExtension[entry.name.substringAfterLast('.', "").lowercase()] ?: Document
    }
}
