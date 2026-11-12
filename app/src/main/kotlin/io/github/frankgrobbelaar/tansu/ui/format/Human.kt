package io.github.frankgrobbelaar.tansu.ui.format

import io.github.frankgrobbelaar.tansu.files.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** Sizes, counts and dates as people say them: "4.2 MB", "128 items", "Today, 09:12". */
object Human {
    private val units = listOf("B", "KB", "MB", "GB", "TB")
    private const val STEP = 1000.0
    private const val ONE_DECIMAL_BELOW = 10

    /** A size in Android's units (1 KB is 1,000 bytes), one decimal below 10. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        var value = bytes.toDouble()
        var unit = 0
        while (abs(value) >= STEP && unit < units.lastIndex) {
            value /= STEP
            unit++
        }
        val number =
            if (unit == 0 || value >= ONE_DECIMAL_BELOW) "%.0f".format(locale, value)
            else "%.1f".format(locale, value)
        return "$number ${units[unit]}"
    }

    fun count(items: Int, locale: Locale = Locale.getDefault()): String =
        "%,d %s".format(locale, items, if (items == 1) "item" else "items")

    /** "Today", "Yesterday", "14 Mar" or "3 Jan 2024"; today's and yesterday's with the time. */
    fun date(
        millis: Long,
        withTime: Boolean,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        val today = LocalDate.ofInstant(now, zone)
        val day = moment.toLocalDate()
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return when (day) {
            today -> if (withTime) "Today, ${moment.format(time)}" else "Today"
            today.minusDays(1) -> if (withTime) "Yesterday, ${moment.format(time)}" else "Yesterday"
            else -> {
                val pattern = if (day.year == today.year) "d MMM" else "d MMM yyyy"
                moment.format(DateTimeFormatter.ofPattern(pattern, locale))
            }
        }
    }

    /** The line under a name in Browse: a folder's count and day, or a file's size and time. */
    fun meta(
        entry: Entry,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val first =
            when {
                !entry.isFolder -> size(entry.size, locale)
                entry.childCount == 0 -> "Empty"
                else -> entry.childCount?.let { count(it, locale) }
            }
        val second = date(entry.modified, withTime = !entry.isFolder, now, zone, locale)
        return listOfNotNull(first, second).joinToString(" · ")
    }
}
