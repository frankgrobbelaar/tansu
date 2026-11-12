package io.github.frankgrobbelaar.tansu

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
        val updates = (application as TansuApplication).container.updates
        // Each time tansu comes up, it asks GitHub for a new version if a day has gone by.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { updates.checkIfDue() }
        }
        setContent { ChosenTheme { TansuRoot() } }
    }
}
