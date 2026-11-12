package io.github.frankgrobbelaar.tansu

import android.app.Application

open class TansuApplication : Application() {
    open val container: AppContainer by lazy { AppContainer(this) }
}
