@file:Suppress("MaxLineLength") // SVG path data reads best as one string per stroke.

package io.github.frankgrobbelaar.tansu.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Kiri's icons: thin rounded lines on a 24-unit grid, taken from the design boards. Each is drawn
 * in black and tinted by `Icon`, so it takes the colour of whatever holds it.
 */
object TansuIcons {
    val Back by lazy { icon(line("M19 12H5"), line("M11 18l-6-6 6-6")) }
    val Close by lazy { icon(line("M6 6l12 12M18 6L6 18")) }
    val Search by lazy {
        icon(line("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0"), line("M20 20l-4.2-4.2"))
    }
    val More by lazy {
        icon(
            solid("M10.5 5.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
            solid("M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
            solid("M10.5 18.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
        )
    }
    val MoreHorizontal by lazy {
        icon(
            solid("M4 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
            solid("M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
            solid("M17 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
        )
    }
    val Folder by lazy { icon(line(FOLDER)) }
    val NewFolder by lazy { icon(line(FOLDER), line("M12 11v5M9.5 13.5h5")) }
    val MissingFolder by lazy {
        icon(
            line(FOLDER),
            line("M10.2 12.2a1.9 1.9 0 1 1 2.6 1.7c-.5.2-.8.6-.8 1.1"),
            line("M12 17h.01"),
        )
    }
    val Picture by lazy {
        icon(
            line("M5.5 4.5h13a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z"),
            line("M3.5 16l5-5 4 4 3-3 5 5"),
            line("M14 8.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
        )
    }
    val Video by lazy {
        icon(
            line(
                "M6 5.5h12a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5H6a2.5 2.5 0 0 1-2.5-2.5V8A2.5 2.5 0 0 1 6 5.5z"
            ),
            solid("M10.5 9.6v4.8l4-2.4z"),
        )
    }
    val Document by lazy {
        icon(
            line("M7 3.5h6.5l4.5 4.5v10.5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z"),
            line("M13.5 3.5V8H18"),
            line("M8.5 12.5h7M8.5 16h5"),
        )
    }
    val Archive by lazy {
        icon(
            line(
                "M4.5 4h15a1.5 1.5 0 0 1 1.5 1.5v1.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 7V5.5A1.5 1.5 0 0 1 4.5 4z"
            ),
            line("M4.5 8.5v9.5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V8.5"),
            line("M10 12.5h4"),
        )
    }
    val Audio by lazy {
        icon(
            line("M9 17.5V6.5l10-2v11"),
            line("M4 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
            line("M14 15.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
        )
    }
    val Phone by lazy {
        icon(
            line(
                "M9 2.5h6a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1-2.5 2.5H9A2.5 2.5 0 0 1 6.5 19V5A2.5 2.5 0 0 1 9 2.5z"
            ),
            line("M10.5 18.5h3"),
        )
    }
    val Nas by lazy {
        icon(
            line(
                "M5.5 4.5h13a2 2 0 0 1 2 2v2.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2z"
            ),
            line("M5.5 13h13a2 2 0 0 1 2 2v2.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V15a2 2 0 0 1 2-2z"),
            line("M7.5 7.75h.01M7.5 16.25h.01"),
        )
    }
    val Server by lazy {
        icon(
            line(
                "M5.5 4.5h13a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17V7a2.5 2.5 0 0 1 2.5-2.5z"
            ),
            line("M7.5 9.5l2.5 2.5-2.5 2.5"),
            line("M12.5 15h4"),
        )
    }
    val Plus by lazy { icon(line("M12 5v14M5 12h14")) }
    val Check by lazy { icon(line("M5 12.5l4.5 4.5L19 7.5")) }
    val Upload by lazy { icon(line("M12 17V6"), line("M7 11l5-5 5 5"), line("M5.5 20h13")) }
    val Download by lazy { icon(line("M12 4v11"), line("M7 10l5 5 5-5"), line("M5.5 20h13")) }
    val Pause by lazy { icon(line("M9 6.5v11M15 6.5v11")) }
    val Play by lazy { icon(solid("M8 5.5v13l10.5-6.5z")) }
    val Transfers by lazy {
        icon(
            line("M8 19V5"),
            line("M4.5 8.5L8 5l3.5 3.5"),
            line("M16 5v14"),
            line("M12.5 15.5L16 19l3.5-3.5"),
        )
    }
    val Settings by lazy {
        icon(
            line("M4 7h8.5M16.5 7H20M4 17h3.5M11.5 17H20"),
            line("M12.5 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
            line("M7.5 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
        )
    }
    val Grid by lazy {
        icon(
            line(
                "M5.5 4h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 4 9.5v-4A1.5 1.5 0 0 1 5.5 4z"
            ),
            line(
                "M14.5 4h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 13 9.5v-4A1.5 1.5 0 0 1 14.5 4z"
            ),
            line(
                "M5.5 13h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 4 18.5v-4A1.5 1.5 0 0 1 5.5 13z"
            ),
            line(
                "M14.5 13h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 13 18.5v-4A1.5 1.5 0 0 1 14.5 13z"
            ),
        )
    }
    val List by lazy {
        icon(line("M8 6.5h12M8 12h12M8 17.5h12"), line("M4 6.5h.01M4 12h.01M4 17.5h.01"))
    }
    val Sort by lazy {
        icon(line("M7 5v14"), line("M4 16l3 3 3-3"), line("M13 7h7M13 12h5M13 17h3"))
    }
    val Rename by lazy { icon(line("M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17z"), line("M14 7l3 3")) }
    val Info by lazy {
        icon(
            line("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
            line("M12 11v5.5"),
            line("M12 7.6h.01"),
        )
    }
    val Share by lazy {
        icon(
            line("M15 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
            line("M4 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
            line("M15 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
            line("M8.7 10.8l6.6-3.9M8.7 13.2l6.6 3.9"),
        )
    }
    val Cut by lazy {
        icon(
            line("M4 7a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
            line("M4 17a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
            line("M8.6 8.4L19 17M8.6 15.6L19 7"),
        )
    }
    val Copy by lazy {
        icon(
            line("M10.5 8.5h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"),
            line("M15.5 8.5V6.5a2 2 0 0 0-2-2h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2"),
        )
    }
    val Delete by lazy {
        icon(
            line("M4.5 6.5h15"),
            line("M9.5 6.5V4.5h5v2"),
            line("M6.5 6.5l.8 12.1a2 2 0 0 0 2 1.9h5.4a2 2 0 0 0 2-1.9l.8-12.1"),
            line("M10 10.5v6M14 10.5v6"),
        )
    }
    val Alert by lazy {
        icon(line("M12 4l9 15.5H3z"), line("M12 10v4.5"), line("M12 17.2h.01"))
    }
    val Offline by lazy {
        icon(
            line("M7 18.5h9.5a4 4 0 0 0 .9-7.9 6 6 0 0 0-10.6-1.8A4.6 4.6 0 0 0 7 18.5z"),
            line("M4 4l16 16"),
        )
    }
    val Key by lazy {
        icon(
            line("M4.5 15a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
            line("M10.5 12.5L19 4"),
            line("M16 7l2.5 2.5"),
            line("M14 9l1.8 1.8"),
        )
    }
    val Lock by lazy {
        icon(
            line(
                "M7.5 10.5h9a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 18v-5a2.5 2.5 0 0 1 2.5-2.5z"
            ),
            line("M8 10.5V8a4 4 0 0 1 8 0v2.5"),
        )
    }
    val Shield by lazy { icon(line(SHIELD), line("M9 12l2.2 2.2L15.5 10")) }
    val ShieldAlert by lazy { icon(line(SHIELD), line("M12 8.5v4"), line("M12 15.6h.01")) }
    val Eye by lazy { icon(line(EYE), line("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")) }
    val EyeOff by lazy { icon(line(EYE), line("M4 4l16 16")) }
    val Refresh by lazy { icon(line("M19.5 12a7.5 7.5 0 1 1-2.2-5.3"), line("M19.5 4.5v4h-4")) }
    val ChevronRight by lazy { icon(line("M9.5 6l6 6-6 6")) }
    val OpenOutside by lazy {
        icon(
            line("M14 4.5h5.5V10"),
            line("M19.5 4.5L11 13"),
            line("M17.5 14v4a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2h4"),
        )
    }
    val SelectAll by lazy {
        icon(
            line("M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z"),
            line("M8 12.5l3 3 5-6"),
        )
    }
    val Update by lazy {
        icon(
            line("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
            line("M12 7.5v8"),
            line("M8.5 12.5L12 16l3.5-3.5"),
        )
    }
}

private const val FOLDER =
    "M3.5 7.5a2 2 0 0 1 2-2h3.6l2.2 2.4h7.2a2 2 0 0 1 2 2v7.6a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"
private const val SHIELD = "M12 3.5l7 2.8v5.2c0 4.3-2.9 7.6-7 9-4.1-1.4-7-4.7-7-9V6.3z"
private const val EYE = "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"

/** One stroke of an icon: a line drawn with Kiri's thin rounded pen, or a filled shape. */
private class Part(val path: String, val filled: Boolean)

private fun line(path: String) = Part(path, filled = false)

private fun solid(path: String) = Part(path, filled = true)

private fun icon(vararg parts: Part): ImageVector =
    ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply {
            parts.forEach { part ->
                addPath(
                    pathData = addPathNodes(part.path),
                    fill = if (part.filled) SolidColor(Color.Black) else null,
                    stroke = if (part.filled) null else SolidColor(Color.Black),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        .build()
