package io.github.frankgrobbelaar.tansu.jobs

import io.github.frankgrobbelaar.tansu.files.Entry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalTest {
    @get:Rule val folder = TemporaryFolder()

    private val picked =
        Entry("IMG_1.jpg", "/DCIM/IMG_1.jpg", isFolder = false, size = 3, modified = 7)
    private val job =
        Job(
            id = 4,
            kind = Job.Kind.Move,
            picks = listOf(picked),
            from = Where("phone", "/DCIM", "Phone", "Phone › DCIM"),
            to = Where("nas", "/Photos", "NAS", "NAS › Photos"),
            clashes = mapOf("IMG_1.jpg" to Clash.Replace),
            token = "abcd1234",
        )

    @Test
    fun `a job comes back as its log has it, the changes since it began made again`() {
        val journal = FileJournal(folder.root)
        journal.begin(job)
        journal.note(
            job.id,
            listOf(
                Change.Placed("/DCIM/IMG_1.jpg", Placement("/Photos/IMG_1.jpg", replace = true)),
                Change.State("/Photos/.tansu-x", "/DCIM/IMG_1.jpg", Stamp(3, 7), null, 0),
            ),
        )
        journal.note(
            job.id,
            listOf(Change.Written("/photos/img_1.jpg"), Change.Done("/DCIM/IMG_1.jpg")),
        )

        val back = FileJournal(folder.root).saved().single()

        assertEquals(job.picks, back.picks)
        assertEquals(job.clashes, back.clashes)
        assertEquals(Placement("/Photos/IMG_1.jpg", replace = true), back.placed["/DCIM/IMG_1.jpg"])
        assertEquals(Stamp(3, 7), back.partialAs)
        assertEquals(setOf("/photos/img_1.jpg"), back.written)
        assertEquals(setOf("/DCIM/IMG_1.jpg"), back.done)
    }

    @Test
    fun `a last line cut short by the process ending is left out`() {
        val journal = FileJournal(folder.root)
        journal.begin(job)
        journal.note(job.id, listOf(Change.Done("/DCIM/IMG_1.jpg")))
        File(folder.root, "4.log").appendText("""{"type":"done","pa""")

        val back = FileJournal(folder.root).saved().single()

        assertEquals(setOf("/DCIM/IMG_1.jpg"), back.done)
    }

    @Test
    fun `an ended job is forgotten, and a log that can't be read is cleared away`() {
        val journal = FileJournal(folder.root)
        journal.begin(job)
        journal.begin(job.copy(id = 5))
        journal.end(job.id)
        File(folder.root, "6.log").writeText("not a job")

        assertEquals(listOf(5L), FileJournal(folder.root).saved().map { it.id })
        assertTrue(File(folder.root, "6.log").exists().not())
    }

    @Test
    fun `beginning again writes the job afresh, with everything it knows`() {
        val journal = FileJournal(folder.root)
        journal.begin(job)
        journal.note(job.id, listOf(Change.Done("/DCIM/old.jpg")))
        journal.begin(job.copy(done = setOf("/DCIM/IMG_1.jpg")))

        assertEquals(setOf("/DCIM/IMG_1.jpg"), FileJournal(folder.root).saved().single().done)
        assertEquals(1, File(folder.root, "4.log").readLines().size)
    }
}
