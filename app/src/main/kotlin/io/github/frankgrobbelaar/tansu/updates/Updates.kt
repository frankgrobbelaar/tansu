package io.github.frankgrobbelaar.tansu.updates

import io.github.frankgrobbelaar.tansu.TansuSettings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Where updates stand: not asked, asking, up to date, one on offer, on its way, or downloaded. */
sealed interface Update {
    /** GitHub hasn't been asked since tansu started. */
    data object Unknown : Update

    data object Checking : Update

    data object UpToDate : Update

    /** GitHub couldn't be asked, or answered something tansu can't read. */
    data object CheckFailed : Update

    data class Available(val release: Release) : Update

    data class Downloading(
        val release: Release,
        val bytesDone: Long = 0,
        val secondsLeft: Long? = null,
    ) : Update

    /** Downloaded and checked against its SHA-256: Android's installer can take it. */
    data class Ready(val release: Release, val apk: File) : Update

    /** The download failed, or didn't match its checksum, and was thrown away. */
    data class DownloadFailed(val release: Release) : Update
}

/** A newer release on GitHub, as far as an update needs to know it. */
@Serializable
data class Release(
    val version: String,
    /** What changed, one line each, as the release's notes list them. */
    val changes: List<String>,
    val apkUrl: String,
    val apkSize: Long,
    val checksumUrl: String,
)

/**
 * Tansu's updates, straight from its GitHub releases: tansu isn't on a store, so it brings its own.
 * Asking GitHub sends nothing about the user. See docs/releasing.md.
 */
class Updates(
    private val installed: String,
    private val cache: File,
    private val settings: TansuSettings,
    private val scope: CoroutineScope,
    private val get: (url: String) -> InputStream = ::httpGet,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<Update>(Update.Unknown)
    private var downloading: Job? = null

    /** Which download is the current one: one that was cancelled or replaced keeps quiet. */
    private val downloads = AtomicInteger()

    val update: StateFlow<Update> = state.asStateFlow()

    init {
        // A version found before tansu last closed is still on offer, without asking again.
        scope.launch {
            val offered = settings.offer.first()?.let(::releaseOf)
            if (offered != null && isNewer(offered.version, installed)) {
                state.compareAndSet(Update.Unknown, Update.Available(offered))
            }
        }
    }

    /** Asks GitHub if a day has gone by since it was last asked, unless that's turned off. */
    suspend fun checkIfDue() {
        val choices = settings.choices.first()
        if (choices.checkUpdates && clock() - choices.lastUpdateCheck >= DAY_MILLIS) check()
    }

    /** Asks GitHub for the newest release, unless an update is already on its way or here. */
    fun check() {
        val before = state.value
        when (before) {
            Update.Checking,
            is Update.Downloading,
            is Update.Ready -> return
            else -> state.value = Update.Checking
        }
        scope.launch {
            settings.updateChecked(clock())
            val found =
                try {
                    val release = latest()
                    if (release != null && isNewer(release.version, installed)) {
                        Update.Available(release)
                    } else {
                        Update.UpToDate
                    }
                } catch (_: IOException) {
                    Update.CheckFailed
                } catch (_: SerializationException) {
                    Update.CheckFailed
                }
            when (found) {
                is Update.Available -> settings.setOffer(format.encodeToString(found.release))
                Update.UpToDate -> settings.setOffer(null)
                else -> Unit
            }
            // GitHub not answering doesn't take back a version already on offer.
            state.value =
                if (found == Update.CheckFailed && before is Update.Available) before else found
        }
    }

    /** Downloads the release on offer, and checks it's the file the release says it is. */
    fun download() {
        val release =
            when (val now = state.value) {
                is Update.Available -> now.release
                is Update.DownloadFailed -> now.release
                else -> return
            }
        val download = downloads.incrementAndGet()
        downloading?.cancel()
        state.value = Update.Downloading(release)
        downloading = scope.launch {
            val ended =
                try {
                    Update.Ready(release, fetch(release, download))
                } catch (_: IOException) {
                    Update.DownloadFailed(release)
                }
            if (download == downloads.get()) state.value = ended
        }
    }

    /** Stops a download; the release stays on offer. */
    fun cancel() {
        val now = state.value as? Update.Downloading ?: return
        downloads.incrementAndGet()
        downloading?.cancel()
        state.value = Update.Available(now.release)
    }

    private suspend fun latest(): Release? =
        withContext(io) {
            // An update from before is either installed or given up on.
            File(cache, FOLDER).deleteRecursively()
            val json = get(LATEST).bufferedReader().use { it.readText() }
            val release = format.decodeFromString<GitHubRelease>(json)
            // Only files on tansu's own releases: tansu talks to GitHub and nowhere else.
            val files = release.assets.filter { it.url.startsWith(DOWNLOADS) }
            val apk = files.find { it.name == APK }
            val checksum = files.find { it.name == CHECKSUM }
            if (apk == null || checksum == null) return@withContext null
            Release(
                version = release.tag.removePrefix("v"),
                changes = changesIn(release.body.orEmpty()),
                apkUrl = apk.url,
                apkSize = apk.size,
                checksumUrl = checksum.url,
            )
        }

    /**
     * Downloads [release]'s APK as the [download]th try, checks it against its SHA-256, and only
     * then gives it its name.
     */
    private suspend fun fetch(release: Release, download: Int): File =
        withContext(io) {
            val folder =
                File(cache, FOLDER).apply {
                    deleteRecursively()
                    mkdirs()
                }
            val part = File(folder, "$APK.$download.part")
            val digest = MessageDigest.getInstance("SHA-256")
            val started = clock()
            get(release.apkUrl).use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(CHUNK)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val left = secondsLeft(done, release.apkSize, clock() - started)
                        state.update {
                            if (it is Update.Downloading && download == downloads.get())
                                it.copy(bytesDone = done, secondsLeft = left)
                            else it
                        }
                    }
                }
            }
            val expected = get(release.checksumUrl).bufferedReader().use { it.readText() }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected.trim().substringBefore(' '), ignoreCase = true)) {
                part.delete()
                throw IOException("The download doesn't match its SHA-256")
            }
            File(folder, APK).also { apk -> if (!part.renameTo(apk)) throw IOException("No room") }
        }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        val size: Long,
        @SerialName("browser_download_url") val url: String,
    )

    companion object {
        /** The cache folder downloads go in; the FileProvider hands it to Android's installer. */
        const val FOLDER = "update"
        const val LATEST = "https://api.github.com/repos/frankgrobbelaar/tansu/releases/latest"
        const val DOWNLOADS = "https://github.com/frankgrobbelaar/tansu/releases/download/"
        private const val APK = "tansu.apk"
        private const val CHECKSUM = "tansu.apk.sha256"
        private const val CHUNK = 64 * 1024
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val MILLIS = 1000
        private val format = Json { ignoreUnknownKeys = true }

        /** A release kept from before, or null if it can't be read back. */
        private fun releaseOf(json: String): Release? =
            try {
                format.decodeFromString<Release>(json)
            } catch (_: SerializationException) {
                null
            }

        /** Seconds to go at the speed so far, once there's a second of it to go by. */
        private fun secondsLeft(done: Long, total: Long, elapsedMillis: Long): Long? =
            if (elapsedMillis < MILLIS || done == 0L) null
            else (total - done).coerceAtLeast(0) * elapsedMillis / done / MILLIS
    }
}

/**
 * Whether version [a] (like 1.2.3) is newer than [b]. Anything that isn't a version isn't newer.
 */
internal fun isNewer(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: return false }
    val y = b.split('.').map { it.toIntOrNull() ?: return false }
    for (part in 0 until maxOf(x.size, y.size)) {
        val difference = x.getOrElse(part) { 0 } - y.getOrElse(part) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}

/**
 * The changes a release's notes list: one per "- " line, with the lines it wraps onto. The notes
 * are the release's part of CHANGELOG.md.
 */
internal fun changesIn(notes: String): List<String> {
    val changes = mutableListOf<String>()
    for (line in notes.lines()) {
        when {
            line.startsWith("- ") -> changes += line.removePrefix("- ").trim()
            line.startsWith("  ") && line.isNotBlank() && changes.isNotEmpty() ->
                changes[changes.lastIndex] += " " + line.trim()
        }
    }
    return changes
}

private const val TIMEOUT_MILLIS = 15_000

/**
 * Reads a URL over HTTPS. GitHub asks every client to name itself: this names nothing about you.
 */
private fun httpGet(url: String): InputStream {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MILLIS
    connection.readTimeout = TIMEOUT_MILLIS
    connection.setRequestProperty("User-Agent", "tansu-updates")
    val code = connection.responseCode
    if (code != HttpURLConnection.HTTP_OK) {
        connection.disconnect()
        throw IOException("HTTP $code from $url")
    }
    return connection.inputStream
}
