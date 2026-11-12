package io.github.frankgrobbelaar.tansu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.frankgrobbelaar.tansu.ui.ChosenTheme
import io.github.frankgrobbelaar.tansu.ui.TansuRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TansuApplication).container
        lifecycle.addObserver(container.front)
        val updates = container.updates
        // Each time tansu comes up, it asks GitHub for a new version if a day has gone by.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { updates.checkIfDue() }
        }
        setContent { ChosenTheme { TansuRoot() } }
        showAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showAsked(intent)
    }

    /** A finished job's notification opens the folder its files went to. */
    private fun showAsked(intent: Intent) {
        if (intent.action != ACTION_SHOW_FOLDER) return
        val drawer = intent.getStringExtra(EXTRA_DRAWER) ?: return
        val folder = intent.getStringExtra(EXTRA_FOLDER) ?: return
        (application as TansuApplication).container.showFolder(drawer, folder)
        // Handled once: turning the phone mustn't show it again.
        setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        private const val ACTION_SHOW_FOLDER = "io.github.frankgrobbelaar.tansu.SHOW_FOLDER"
        private const val EXTRA_DRAWER = "drawer"
        private const val EXTRA_FOLDER = "folder"

        /** Opens tansu on [folder] of the drawer [drawerId]. */
        fun showFolder(context: Context, drawerId: String, folder: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SHOW_FOLDER)
                .putExtra(EXTRA_DRAWER, drawerId)
                .putExtra(EXTRA_FOLDER, folder)
    }
}
