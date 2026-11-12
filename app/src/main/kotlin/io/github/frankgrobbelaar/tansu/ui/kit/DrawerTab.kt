package io.github.frankgrobbelaar.tansu.ui.kit

import io.github.frankgrobbelaar.tansu.files.Drawer

/** A drawer as its chip shows it, with a quiet dot while its server has a problem. */
data class DrawerTab(
    val id: String,
    val name: String,
    val kind: Drawer.Kind,
    val troubled: Boolean = false,
)
