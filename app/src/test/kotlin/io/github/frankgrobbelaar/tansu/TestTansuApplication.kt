package io.github.frankgrobbelaar.tansu

import io.github.frankgrobbelaar.tansu.servers.ReversingCipher
import io.github.frankgrobbelaar.tansu.servers.StoredSecrets
import java.nio.file.Files
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Tansu for Robolectric: a phone that's an empty temporary folder, All files access as [granted]
 * says, and secrets in memory instead of the Android Keystore.
 */
class TestTansuApplication : TansuApplication() {
    override val container: AppContainer by lazy {
        AppContainer(
            context = this,
            storageAccess = { granted },
            phoneRoot = Files.createTempDirectory("tansu-phone").invariantSeparatorsPathString,
            secrets = StoredSecrets(MemoryStore(), ReversingCipher()),
        )
    }

    companion object {
        @Volatile var granted = true
    }
}
