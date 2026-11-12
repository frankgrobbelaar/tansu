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
