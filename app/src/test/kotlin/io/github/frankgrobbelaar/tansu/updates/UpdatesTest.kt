package io.github.frankgrobbelaar.tansu.updates

import io.github.frankgrobbelaar.tansu.MemoryStore
import io.github.frankgrobbelaar.tansu.TansuSettings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesTest {
    @get:Rule val folder = TemporaryFolder()

    private val apk = "the next tansu".toByteArray()
    private var checksum = sha256(apk)
    private var latest: String? = releaseJson("v0.2.0")
    private val asked = mutableListOf<String>()
    private val settings = TansuSettings(MemoryStore())
    private var now = DAY * 100

    private fun get(url: String): InputStream {
        asked += url
        return when (url) {
            Updates.LATEST -> latest?.byteInputStream() ?: throw IOException("offline")
            APK_URL -> apk.inputStream()
            CHECKSUM_URL -> "$checksum\n".byteInputStream()
            else -> throw IOException("HTTP 404 from $url")
        }
    }

    private fun TestScope.updates(
        installed: String = "0.1.0",
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
    ) =
        Updates(
            installed = installed,
            cache = folder.root,
            settings = settings,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            get = ::get,
            io = dispatcher,
            clock = { now },
        )

    @Test
    fun `a newer release is offered with what changed and its size`() = runTest {
        val updates = updates()
        updates.check()
        val release = (updates.update.value as Update.Available).release
        assertEquals("0.2.0", release.version)
        assertEquals(listOf("Settings, with a theme.", "Updates."), release.changes)
        assertEquals(apk.size.toLong(), release.apkSize)
        assertEquals(now, settings.choices.first().lastUpdateCheck)
    }

    @Test
    fun `the version installed is up to date`() = runTest {
        val updates = updates(installed = "0.2.0")
        updates.check()
        assertEquals(Update.UpToDate, updates.update.value)
    }

    @Test
    fun `no answer from GitHub is a check that failed`() = runTest {
        latest = null
        val updates = updates()
        updates.check()
        assertEquals(Update.CheckFailed, updates.update.value)
    }

    @Test
    fun `files that aren't on tansu's own releases are left alone`() = runTest {
        latest = latest!!.replace(APK_URL, "https://example.com/tansu.apk")
        val updates = updates()
        updates.check()
        assertEquals(Update.UpToDate, updates.update.value)
    }

    @Test
    fun `GitHub is asked at most once a day, and not at all when that's turned off`() = runTest {
        val updates = updates(installed = "0.2.0")
        updates.checkIfDue()
        now += DAY / 2
        updates.checkIfDue()
        assertEquals(1, asked.count { it == Updates.LATEST })

        now += DAY
        updates.checkIfDue()
        assertEquals(2, asked.count { it == Updates.LATEST })

        settings.setCheckUpdates(false)
        now += DAY * 2
        updates.checkIfDue()
        assertEquals(2, asked.count { it == Updates.LATEST })
    }

    @Test
    fun `a download that matches its checksum is ready to install`() = runTest {
        val updates = updates()
        updates.check()
        updates.download()
        val ready = updates.update.value as Update.Ready
        assertEquals("0.2.0", ready.release.version)
        assertTrue(ready.apk.readBytes().contentEquals(apk))
    }

    @Test
    fun `a download that doesn't match its checksum is thrown away`() = runTest {
        checksum = sha256("something else".toByteArray())
        val updates = updates()
        updates.check()
        updates.download()
        assertTrue(updates.update.value is Update.DownloadFailed)
        assertFalse(File(folder.root, "${Updates.FOLDER}/tansu.apk").exists())
    }

    @Test
    fun `a cancelled download leaves the release on offer`() = runTest {
        val updates = updates(dispatcher = StandardTestDispatcher(testScheduler))
        updates.check()
        advanceUntilIdle()
        updates.download()
        assertTrue(updates.update.value is Update.Downloading)
        updates.cancel()
        advanceUntilIdle()
        assertTrue(updates.update.value is Update.Available)
        assertFalse(APK_URL in asked)
    }

    @Test
    fun `versions are compared part by part`() {
        assertTrue(isNewer("0.2.0", "0.1.0"))
        assertTrue(isNewer("0.10.0", "0.9.9"))
        assertTrue(isNewer("1.0.0", "0.99.99"))
        assertFalse(isNewer("0.1.0", "0.1.0"))
        assertFalse(isNewer("1.0", "1.0.0"))
        assertFalse(isNewer("0.1.0", "0.2.0"))
        assertFalse(isNewer("next", "0.1.0"))
    }

    @Test
    fun `release notes give one change per bullet, with the lines it wraps onto`() {
        val notes = "The first version.\n\n- Browse the phone,\n  and servers.\n- Jobs.\n"
        assertEquals(listOf("Browse the phone, and servers.", "Jobs."), changesIn(notes))
    }

    private fun releaseJson(tag: String) =
        """
        {
          "tag_name": "$tag",
          "body": "What's new.\n\n- Settings, with\n  a theme.\n- Updates.",
          "assets": [
            {"name": "tansu.apk", "size": ${apk.size}, "browser_download_url": "$APK_URL"},
            {"name": "tansu.apk.sha256", "size": 65, "browser_download_url": "$CHECKSUM_URL"}
          ]
        }
        """

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val APK_URL = "${Updates.DOWNLOADS}v0.2.0/tansu.apk"
        const val CHECKSUM_URL = "$APK_URL.sha256"

        fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
