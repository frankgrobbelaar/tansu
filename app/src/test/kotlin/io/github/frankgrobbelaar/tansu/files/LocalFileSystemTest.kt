package io.github.frankgrobbelaar.tansu.files

import java.io.File
import kotlin.io.invariantSeparatorsPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileSystemTest {
    @get:Rule val folder = TemporaryFolder()

    private val files = LocalFileSystem()

    @Test
    fun `lists files and folders with their sizes, dates and counts`() = runTest {
        val photos = folder.newFolder("Photos")
        File(photos, "a.jpg").writeText("a")
        File(photos, "b.jpg").writeText("b")
        val notes = folder.newFile("notes.txt").apply { writeText("hello") }
        notes.setLastModified(1_700_000_000_000)

        val entries = files.list(folder.root.path).associateBy { it.name }

        val listedPhotos = entries.getValue("Photos")
        assertTrue(listedPhotos.isFolder)
        assertEquals(2, listedPhotos.childCount)
        assertEquals(photos.invariantSeparatorsPath, listedPhotos.path)
        val listedNotes = entries.getValue("notes.txt")
        assertEquals(5L, listedNotes.size)
        assertEquals(1_700_000_000_000, listedNotes.modified)
        assertNull(listedNotes.childCount)
    }

    @Test
    fun `marks names that start with a dot as hidden`() = runTest {
        folder.newFile(".nomedia")

        assertTrue(files.list(folder.root.path).single().isHidden)
    }

    @Test
    fun `a folder that isn't there is not found`() {
        val missing = File(folder.root, "gone").path

        assertThrows(FileSystemException.NotFound::class.java) {
            kotlinx.coroutines.runBlocking { files.list(missing) }
        }
    }

    @Test
    fun `a file is not a folder to list`() {
        val file = folder.newFile("plain.txt").path

        assertThrows(FileSystemException.NotFound::class.java) {
            kotlinx.coroutines.runBlocking { files.list(file) }
        }
    }
}
