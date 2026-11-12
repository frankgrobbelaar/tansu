package io.github.frankgrobbelaar.tansu.ui.transfers

import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.jobs.Failure
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.jobs.Placement
import io.github.frankgrobbelaar.tansu.jobs.Progress
import io.github.frankgrobbelaar.tansu.jobs.Where
import org.junit.Assert.assertEquals
import org.junit.Test

class PickStateTest {
    private val photo = Entry("a.jpg", "/DCIM/a.jpg", isFolder = false, size = 1, modified = 0)
    private val trip = Entry("Trip", "/DCIM/Trip", isFolder = true, size = 0, modified = 0)
    private val skipped = Entry("b.jpg", "/DCIM/b.jpg", isFolder = false, size = 1, modified = 0)
    private val job =
        Job(
            id = 1,
            kind = Job.Kind.Copy,
            picks = listOf(photo, trip, skipped),
            from = Where("phone", "/DCIM", "Phone", "Phone › DCIM"),
            to = Where("nas", "/Photos", "NAS", "NAS › Photos"),
            placed =
                mapOf(
                    photo.path to Placement("/Photos/a.jpg", replace = false),
                    trip.path to Placement("/Photos/Trip", replace = false),
                ),
        )

    @Test
    fun `picks wait, then one is on the go while the rest wait`() {
        assertEquals(PickState.Waiting, stateOf(photo, job))
        val running =
            job.copy(
                status = Job.Status.Running,
                done = setOf(photo.path),
                progress = Progress(currentPath = "/DCIM/Trip/c.jpg"),
            )
        assertEquals(PickState.Done, stateOf(photo, running))
        assertEquals(PickState.Now, stateOf(trip, running))
        assertEquals(PickState.Waiting, stateOf(skipped, running))
    }

    @Test
    fun `a finished job's folders are done, unplaced picks skipped, and failures said`() {
        val finished = job.copy(status = Job.Status.Done, done = setOf(photo.path))
        assertEquals(PickState.Done, stateOf(trip, finished))
        assertEquals(PickState.Skipped, stateOf(skipped, finished))

        val failed =
            finished.copy(
                failures = listOf(Failure("c.jpg", "/DCIM/Trip/c.jpg", Failure.Reason.NoSpace))
            )
        assertEquals(PickState.Failed, stateOf(trip, failed))
    }
}
