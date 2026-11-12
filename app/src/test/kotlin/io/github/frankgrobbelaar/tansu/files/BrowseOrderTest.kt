package io.github.frankgrobbelaar.tansu.files

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseOrderTest {
    private fun file(name: String) = Entry(name, "/$name", isFolder = false, size = 1, modified = 0)

    private fun folder(name: String) =
        Entry(name, "/$name", isFolder = true, size = 0, modified = 0)

    @Test
    fun `folders come first, then files`() {
        val sorted =
            listOf(file("a.txt"), folder("Zebra"), file("B.txt"), folder("apples"))
                .sortedWith(BrowseOrder)

        assertEquals(listOf("apples", "Zebra", "a.txt", "B.txt"), sorted.map { it.name })
    }

    @Test
    fun `files sort by date, size or type either way, with folders still first`() {
        val entries =
            listOf(
                Entry("b.txt", "/b.txt", isFolder = false, size = 30, modified = 1),
                Entry("a.jpg", "/a.jpg", isFolder = false, size = 10, modified = 3),
                Entry("c.mp4", "/c.mp4", isFolder = false, size = 20, modified = 2),
                folder("Zed"),
                folder("Alpha"),
            )
        fun order(sort: Sort) = entries.sortedWith(browseOrder(sort)).map { it.name }

        assertEquals(
            listOf("Alpha", "Zed", "b.txt", "c.mp4", "a.jpg"),
            order(Sort(SortBy.Modified)),
        )
        assertEquals(
            listOf("Zed", "Alpha", "a.jpg", "c.mp4", "b.txt"),
            order(Sort(SortBy.Modified, descending = true)),
        )
        assertEquals(listOf("Alpha", "Zed", "a.jpg", "c.mp4", "b.txt"), order(Sort(SortBy.Size)))
        assertEquals(listOf("Alpha", "Zed", "a.jpg", "c.mp4", "b.txt"), order(Sort(SortBy.Type)))
        assertEquals(
            listOf("Zed", "Alpha", "c.mp4", "b.txt", "a.jpg"),
            order(Sort(SortBy.Name, descending = true)),
        )
    }

    @Test
    fun `numbers count up the way people read them`() {
        val names = listOf("IMG_10.jpg", "IMG_2.jpg", "IMG_1.jpg", "img_3.jpg")

        val sorted = names.map(::file).sortedWith(BrowseOrder).map { it.name }

        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "img_3.jpg", "IMG_10.jpg"), sorted)
    }

    @Test
    fun `leading zeros don't change a number's place`() {
        assertEquals(-1, Integer.signum(naturalCompare("track 007", "track 10")))
        assertEquals(1, Integer.signum(naturalCompare("track 12", "track 007")))
    }

    @Test
    fun `digits from other scripts sort as letters, so the order stays consistent`() {
        val fullwidthOne = "\uFF11"

        val sorted = listOf(fullwidthOne, "a", "10").sortedWith(::naturalCompare)

        assertEquals(listOf("10", "a", fullwidthOne), sorted)
        assertEquals(1, Integer.signum(naturalCompare(fullwidthOne, "10")))
    }
}
