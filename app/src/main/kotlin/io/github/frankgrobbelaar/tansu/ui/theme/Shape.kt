package io.github.frankgrobbelaar.tansu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Kiri's soft corners on Material's scale: 8 small, 14 medium, 20 large and 28 for dialogs and
 * sheets. Material draws text fields with extraSmall and cards with medium, so the kit gives those
 * Kiri's own radii from [KiriCorners].
 */
internal val KiriShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/** The corners docs/design.md names outside Material's scale. */
object KiriCorners {
    val Chip = RoundedCornerShape(10.dp)
    val Field = RoundedCornerShape(12.dp)
    val Tile = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(18.dp)
}
