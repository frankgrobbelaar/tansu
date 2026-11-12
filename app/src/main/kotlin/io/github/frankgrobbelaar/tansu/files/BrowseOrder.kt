package io.github.frankgrobbelaar.tansu.files

/**
 * Browse's order: folders first, then files, each by name as a person reads it. Case doesn't matter
 * and numbers count up, so `IMG_2` comes before `IMG_10`.
 */
val BrowseOrder: Comparator<Entry> =
    compareByDescending<Entry> { it.isFolder }
        .thenComparator { a, b -> naturalCompare(a.name, b.name) }

/**
 * Compares names character by character, ignoring case, but reads each run of the digits 0 to 9 as
 * one number. It walks the names in place, since a big folder makes many comparisons.
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    var difference = 0
    while (difference == 0 && i < a.length && j < b.length) {
        if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
            val aEnd = digitsEnd(a, i)
            val bEnd = digitsEnd(b, j)
            difference = compareNumbers(a.substring(i, aEnd), b.substring(j, bEnd))
            i = aEnd
            j = bEnd
        } else {
            difference = a[i].folded().compareTo(b[j].folded())
            i++
            j++
        }
    }
    return when {
        difference != 0 -> difference
        a.length - i != b.length - j -> (a.length - i).compareTo(b.length - j)
        else -> a.compareTo(b)
    }
}

/** Only 0 to 9: other scripts' digits sort as letters, which keeps the order consistent. */
private fun Char.isAsciiDigit() = this in '0'..'9'

/** Case folded the way `String.compareTo(ignoreCase = true)` does it. */
private fun Char.folded() = uppercaseChar().lowercaseChar()

private fun digitsEnd(name: String, start: Int): Int {
    var end = start
    while (end < name.length && name[end].isAsciiDigit()) end++
    return end
}

private fun compareNumbers(a: String, b: String): Int {
    val left = a.trimStart('0')
    val right = b.trimStart('0')
    return left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
}
