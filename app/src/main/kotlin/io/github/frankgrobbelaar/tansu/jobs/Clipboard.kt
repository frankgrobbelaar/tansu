package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Entry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Cut or Copy picked up, carried to whichever folder it's pasted in. */
class Clipboard {
    private val held = MutableStateFlow<Held?>(null)

    val contents: StateFlow<Held?> = held.asStateFlow()

    fun hold(picks: List<Entry>, from: Where, cut: Boolean) {
        held.value = Held(picks, from, cut)
    }

    fun clear() {
        held.value = null
    }
}

/** Picked files and folders, where they came from, and whether they're to be moved. */
data class Held(val picks: List<Entry>, val from: Where, val cut: Boolean) {
    val bytes: Long
        get() = picks.sumOf { it.size }
}
