package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.MemoryFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** Three drawers, one of them case-blind, with a few photos on the phone, and jobs between them. */
@OptIn(ExperimentalCoroutinesApi::class)
open class JobsFixture {
    protected val phone = MemoryFileSystem()
    protected val nas = MemoryFileSystem()
    protected val share = MemoryFileSystem(ignoreCase = true)
    protected val drawers =
        MutableStateFlow(
            listOf(
                Drawer("phone", "Phone", Drawer.Kind.Phone, "/", phone),
                Drawer("nas", "NAS", Drawer.Kind.Sftp, "/", nas),
                Drawer("share", "Share", Drawer.Kind.Smb, "/", share),
            )
        )
    protected val camera = Where("phone", "/DCIM/Camera", "Phone", "Phone › DCIM › Camera")
    protected val photos = Where("nas", "/Photos", "NAS", "NAS › Photos")
    protected val media = Where("share", "/Media", "Share", "Share › Media")

    protected fun TestScope.jobs() =
        Jobs(
            drawers = drawers,
            // Not backgroundScope: advanceUntilIdle leaves background work waiting.
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            io = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

    protected fun entry(files: MemoryFileSystem, path: String): Entry = runBlocking {
        checkNotNull(files.stat(path))
    }

    protected fun Jobs.job(id: Long) = all.value.single { it.id == id }

    init {
        phone.file("/DCIM/Camera/IMG_1.jpg", "one").file("/DCIM/Camera/IMG_2.jpg", "two!")
        phone.file("/DCIM/Camera/Trip/IMG_3.jpg", "three")
        nas.folderAt("/Photos")
        share.folderAt("/Media")
    }
}
