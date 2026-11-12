package io.github.frankgrobbelaar.tansu

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.frankgrobbelaar.tansu.ui.viewer.drawerImageLoader
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

open class TansuApplication : Application(), SingletonImageLoader.Factory {
    open val container: AppContainer by lazy { AppContainer(this) }

    /** Pictures load straight from the drawers, the phone's and the servers'. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        drawerImageLoader(context, container.drawers.all)

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down BouncyCastle as "BC". SFTP's key exchange needs the whole one.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
