package io.github.frankgrobbelaar.tansu.files

/**
 * Browse's order: folders first, then files, each by name as a person reads it. Case doesn't matter
 * and numbers count up, so `IMG_2` comes before `IMG_10`.
 */
val BrowseOrder: Comparator<Entry> =
    compareByDescending<Entry> { it.isFolder }
        .thenComparator { a, b -> naturalCompare(a.name, b.name) }

/** Compares names piece by piece, reading runs of digits as numbers. */
fun naturalCompare(a: String, b: String): Int {
    val left = pieces(a)
    val right = pieces(b)
    for (index in 0 until minOf(left.size, right.size)) {
        val difference = comparePieces(left[index], right[index])
        if (difference != 0) return difference
    }
    return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
}

private val piece = Regex("""\d+|\D+""")

private fun pieces(name: String): List<String> = piece.findAll(name).map { it.value }.toList()

private fun comparePieces(a: String, b: String): Int =
    if (a.first().isDigit() && b.first().isDigit()) {
        val numbers = a.trimStart('0').padStart(1, '0') to b.trimStart('0').padStart(1, '0')
        numbers.first.length.compareTo(numbers.second.length).takeIf { it != 0 }
            ?: numbers.first.compareTo(numbers.second)
    } else {
        a.compareTo(b, ignoreCase = true)
    }
