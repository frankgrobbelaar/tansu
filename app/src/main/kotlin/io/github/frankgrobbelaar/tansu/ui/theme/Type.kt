package io.github.frankgrobbelaar.tansu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.frankgrobbelaar.tansu.R

private fun figtree(weight: Int) =
    Font(
        R.font.figtree,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

/** Figtree, bundled: one variable font file, five weights. */
internal val Figtree =
    FontFamily(figtree(400), figtree(500), figtree(600), figtree(700), figtree(800))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = Figtree,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = FontWeight(weight),
        letterSpacing = tracking.em,
    )

/**
 * Kiri's type scale on Material's roles. Headline 22, title 15, body 13 and label 12 are the four
 * sizes in docs/design.md; the rest fill Material's other roles in the same voice.
 */
internal val KiriTypography =
    Typography(
        displaySmall = style(size = 28, line = 34, weight = 800, tracking = -0.02),
        headlineMedium = style(size = 26, line = 32, weight = 800, tracking = -0.02),
        headlineSmall = style(size = 22, line = 28, weight = 800, tracking = -0.01),
        titleLarge = style(size = 22, line = 28, weight = 700, tracking = -0.01),
        titleMedium = style(size = 15, line = 20, weight = 600),
        titleSmall = style(size = 14, line = 20, weight = 700),
        bodyLarge = style(size = 15, line = 23, weight = 400),
        bodyMedium = style(size = 14, line = 21, weight = 400),
        bodySmall = style(size = 13, line = 18, weight = 400),
        labelLarge = style(size = 15, line = 20, weight = 700),
        labelMedium = style(size = 12, line = 16, weight = 600),
        labelSmall = style(size = 11, line = 14, weight = 700, tracking = 0.08),
    )
