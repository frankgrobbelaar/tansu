package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Entry

/**
 * One copy, move or delete: what it works on, where it puts things, and how far it got. Jobs are
 * immutable; the engine replaces a job with its next state as it runs.
 */
data class Job(
    val id: Long,
    val kind: Kind,
    /** What was picked, all from one folder of one drawer. */
    val picks: List<Entry>,
    val from: Where,
    /** Where copies and moves go; none for a delete. */
    val to: Where?,
    /** What to do about each picked name that's already in the target folder. */
    val clashes: Map<String, Clash> = emptyMap(),
    val status: Status = Status.Queued,
    val progress: Progress = Progress(),
    val failures: List<Failure> = emptyList(),
    /** Source paths already done, so a resumed job carries on where it was. */
    val done: Set<String> = emptySet(),
    /**
     * Where each pick went, by its path, once its clash was settled. A resumed or retried job
     * carries on there instead of asking again.
     */
    val placed: Map<String, String> = emptyMap(),
    /** Picks kept under a new name because the name was taken. */
    val keptBoth: Int = 0,
    val skipped: Int = 0,
    val finishedAt: Long? = null,
) {
    enum class Kind {
        Copy,
        Move,
        Delete,
    }

    sealed interface Status {
        data object Queued : Status

        data object Running : Status

        /** Paused by the user, or because [lost] stopped answering; resuming carries on. */
        data class Paused(val lost: String? = null) : Status

        data object Done : Status

        data object Cancelled : Status
    }

    /** Still to finish: queued, running or paused. */
    val active: Boolean
        get() = status !is Status.Done && status !is Status.Cancelled

    /** Every pick's bytes, as far as the picks say; folders count once they're walked. */
    val pickedBytes: Long
        get() = picks.sumOf { it.size }
}

/** A folder in a drawer, with the names the UI shows for it. */
data class Where(
    val drawerId: String,
    val folder: String,
    val drawerName: String,
    /** The folder as a person reads it, such as "NAS › Media › Photos". */
    val label: String,
)

/** What to do when a picked name is already in the target folder. */
enum class Clash {
    /** Copy it under a new name, such as "IMG_2041 (1).jpg". The safe choice. */
    KeepBoth,
    /** Replace the file that's there; a folder is merged into the one that's there. */
    Replace,
    Skip,
}

data class Progress(
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    /** The file being worked on. */
    val current: String? = null,
    val bytesPerSecond: Long = 0,
    /** Still walking folders to find out how much there is. */
    val counting: Boolean = true,
) {
    val fraction: Float
        get() =
            when {
                bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
                itemsTotal > 0 -> itemsDone.toFloat() / itemsTotal
                else -> 0f
            }

    /** Seconds left at the current speed, when there's a speed to go by. */
    val secondsLeft: Long?
        get() =
            if (bytesPerSecond <= 0 || bytesTotal <= 0) null
            else (bytesTotal - bytesDone).coerceAtLeast(0) / bytesPerSecond
}

/** A file that didn't make it, and why. */
data class Failure(val name: String, val path: String, val reason: Reason) {
    enum class Reason {
        NotFound,
        AccessDenied,
        NoSpace,
        Exists,
        Unreadable,
        Unreachable,
        /** A folder can't be copied or moved into itself. */
        IntoItself,
        /** The copy's size didn't match, so the original was kept. */
        Mismatch,
    }
}
