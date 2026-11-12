package io.github.frankgrobbelaar.tansu

import android.app.Application
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

open class TansuApplication : Application() {
    open val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down BouncyCastle as "BC". SFTP's key exchange needs the whole one.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
