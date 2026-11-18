package tachiyomi.domain.translation.model

/**
 * Where a translated block was painted on the page, in the page view's coordinates.
 *
 * Deliberately free of Android types: this is the geometry of a tap, and it is checked by a plain unit test instead of
 * on a device.
 */
data class TranslatedBlockArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The position of the area under a point, or -1 when the point is on the art instead of a translation.
 *
 * [slop] widens every area: a finger covers more than a pixel, so a tap that lands just outside the lettering still
 * means the bubble it touched. When areas overlap the last one wins, which is the one drawn on top.
 */
fun List<TranslatedBlockArea>.indexAt(x: Float, y: Float, slop: Float): Int =
    indexOfLast { area ->
        x >= area.left - slop &&
            x <= area.right + slop &&
            y >= area.top - slop &&
            y <= area.bottom + slop
    }
