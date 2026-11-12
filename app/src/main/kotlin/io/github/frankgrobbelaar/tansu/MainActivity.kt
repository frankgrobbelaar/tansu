package io.github.frankgrobbelaar.tansu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.frankgrobbelaar.tansu.ui.TansuRoot
import io.github.frankgrobbelaar.tansu.ui.theme.TansuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TansuTheme { TansuRoot() } }
    }
}
