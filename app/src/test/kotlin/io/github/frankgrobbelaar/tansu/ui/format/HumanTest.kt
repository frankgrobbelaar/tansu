package io.github.frankgrobbelaar.tansu.ui.format

import io.github.frankgrobbelaar.tansu.files.Entry
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HumanTest {
    private val locale = Locale.UK
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-12T18:30:00Z")

    private fun at(moment: String) = Instant.parse(moment).toEpochMilli()

    @Test
    fun `sizes use Android's units with one decimal below ten`() {
        assertEquals("12 B", Human.size(12, locale))
        assertEquals("640 KB", Human.size(640_000, locale))
        assertEquals("4.2 MB", Human.size(4_200_000, locale))
        assertEquals("182 MB", Human.size(182_000_000, locale))
        assertEquals("1.4 GB", Human.size(1_400_000_000, locale))
    }

    @Test
    fun `counts say item or items and group thousands`() {
        assertEquals("1 item", Human.count(1, locale))
        assertEquals("1,204 items", Human.count(1204, locale))
    }

    @Test
    fun `dates are relative for today and yesterday, short otherwise`() {
        fun date(moment: String, withTime: Boolean) =
            Human.date(at(moment), withTime, now, zone, locale)

        assertEquals("Today, 09:12", date("2026-11-12T09:12:00Z", withTime = true))
        assertEquals("Today", date("2026-11-12T09:12:00Z", withTime = false))
        assertEquals("Yesterday", date("2026-11-11T23:59:00Z", withTime = false))
        assertEquals("14 Mar", date("2026-03-14T10:00:00Z", withTime = true))
        assertEquals("3 Jan 2024", date("2024-01-03T10:00:00Z", withTime = false))
    }

    @Test
    fun `a folder's line counts what it holds, a file's gives its size and time`() {
        val folder = Entry("2026", "/2026", true, 0, at("2026-11-12T08:00:00Z"), childCount = 128)
        val file = Entry("a.jpg", "/a.jpg", false, 4_200_000, at("2026-11-12T09:12:00Z"))

        assertEquals("128 items · Today", Human.meta(folder, now, zone, locale))
        assertEquals("Empty · Today", Human.meta(folder.copy(childCount = 0), now, zone, locale))
        assertEquals("4.2 MB · Today, 09:12", Human.meta(file, now, zone, locale))
    }
}
