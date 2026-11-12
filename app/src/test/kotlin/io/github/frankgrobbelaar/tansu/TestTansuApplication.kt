package io.github.frankgrobbelaar.tansu

import java.nio.file.Files

/**
 * Tansu for Robolectric: All files access granted, and a phone that's an empty temporary folder.
 */
class TestTansuApplication : TansuApplication() {
    override val container: AppContainer by lazy {
        AppContainer(
            context = this,
            storageAccess = { true },
            phoneRoot = Files.createTempDirectory("tansu-phone").toString(),
        )
    }
}
