package tachiyomi.domain.translation.model

import kotlin.math.abs

/**
 * Brightness of the page at a point, 0 for black and 1 for white.
 */
fun interface PageBrightness {
    fun at(x: Int, y: Int): Float
}

/**
 * The colour of the page at a point, as the recognizer's page thumbnail holds it.
 */
fun interface PageColor {
    fun at(x: Int, y: Int): Int
}

/**
 * The shape a translated block is painted over.
 *
 * Not every bubble is a rectangle. Painted as a rectangle, a translation shows four corners of flat colour over round
 * art, which is exactly what makes a translated page look pasted together. A speech bubble is either a box, a box with
 * rounded corners or a stadium — an oval whose ends are half circles — and telling them apart costs four samples.
 */
enum class PlateShape {
    /** The corners of the detected area are still inside the bubble: a panel or a caption box. */
    RECT,

    /** A bubble with noticeably rounded corners. */
    ROUNDED,

    /** An oval bubble, whose corners sit on the art around it. */
    STADIUM,
}

/**
 * Where a block's translation is painted, and in what colour.
 *
 * [color] is the colour of the bubble behind the original lettering, so the plate disappears into the art instead of
 * covering it with a flat rectangle. [dark] says whether that colour is dark enough for light lettering.
 */
data class BubblePlate(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val shape: PlateShape,
    val cornerRadius: Float,
    val color: Int,
    val dark: Boolean,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Works out the plate of one recognized block from the page around it.
 *
 * The reading is deliberately small: a strip just outside the lettering gives the bubble's colour, walking outwards
 * from each side finds where the bubble ends, and the four corners of what is left say whether the bubble is oval or
 * boxed. Every reading is a sample of the page thumbnail, so a whole page costs a few hundred of them.
 *
 * The plate never grows past [MAX_GROWTH] of the lettering box, so a wrong reading costs a slightly generous plate
 * instead of a rectangle painted over half the panel. When the page can't be read around the text at all, the caller
 * is told so and keeps the plain plate it drew before.
 */
object BubblePlateDetector {

    /** The bubble a block sits in, or null when the page can't be read around it. */
    fun detect(
        textLeft: Float,
        textTop: Float,
        textRight: Float,
        textBottom: Float,
        width: Int,
        height: Int,
        brightness: PageBrightness,
        color: PageColor? = null,
    ): BubblePlate? {
        if (width <= 0 || height <= 0) return null
        val box = Box(textLeft, textTop, textRight, textBottom)
        if (box.width < 2f || box.height < 2f) return null

        val page = Page(width, height, brightness)
        val ring = readRing(box, page) ?: return null
        val background = ring.median
        // Lettering that doesn't stand out from its surroundings can't say which of the two is the bubble, so this
        // block keeps the plain plate the caller falls back to.
        if (ring.spread > MAX_SPREAD) return null

        // How far to look for the bubble's edge. Measured on the longer side of the lettering: a tall narrow bubble
        // still needs room sideways, and a caption needs room above and below.
        val reach = (maxOf(box.width, box.height) * MAX_GROWTH).coerceIn(4f, MAX_REACH)
        val left = walkOut(box, -1, 0, reach, background, page)
        val right = walkOut(box, 1, 0, reach, background, page)
        val top = walkOut(box, 0, -1, reach, background, page)
        val bottom = walkOut(box, 0, 1, reach, background, page)
        val plate = Box(left, top, right, bottom)

        // The corners are what separates an oval from a box: on an oval they land on the art around the bubble.
        val straightCorners = cornersInside(plate, background, page)
        val shape = when {
            straightCorners >= 3 -> PlateShape.RECT
            straightCorners >= 1 -> PlateShape.ROUNDED
            else -> PlateShape.STADIUM
        }
        val shortest = minOf(plate.width, plate.height)
        val radius = when (shape) {
            PlateShape.RECT -> shortest * STRAIGHT_CORNER
            PlateShape.ROUNDED -> shortest * ROUNDED_CORNER
            PlateShape.STADIUM -> shortest / 2f
        }

        return BubblePlate(
            left = plate.left,
            top = plate.top,
            right = plate.right,
            bottom = plate.bottom,
            shape = shape,
            cornerRadius = radius,
            color = color?.let { readColor(plate, page, it) } ?: TRANSPARENT,
            dark = background < DARK_THRESHOLD,
        )
    }

    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }

    private data class Ring(val median: Float, val spread: Float)

    /** The page as the detector reads it: sized and free of platform types. */
    private class Page(
        private val width: Int,
        private val height: Int,
        private val brightness: PageBrightness,
    ) {
        fun sample(x: Float, y: Float): Float {
            val px = x.toInt()
            val py = y.toInt()
            if (px < 0 || py < 0 || px >= width || py >= height) return -1f
            return brightness.at(px, py)
        }
    }

    /**
     * The bubble's colour, read from a strip just outside the lettering: a strip inside it would mostly be text.
     */
    private fun readRing(box: Box, page: Page): Ring? {
        val thickness = (minOf(box.width, box.height) * RING).coerceIn(1f, 6f)
        val samples = ArrayList<Float>(48)
        for (step in 0 until STEPS) {
            val t = (step + 0.5f) / STEPS
            val x = box.left + box.width * t
            val y = box.top + box.height * t
            // Above and below, then left and right: the lettering box is tight around the letters, so a strip just
            // outside it belongs to the bubble in every direction.
            samples += page.sample(x, box.top - thickness)
            samples += page.sample(x, box.bottom + thickness)
            samples += page.sample(box.left - thickness, y)
            samples += page.sample(box.right + thickness, y)
        }
        samples.removeAll { it < 0f }
        if (samples.size < 8) return null
        samples.sort()
        return Ring(
            median = samples[samples.size / 2],
            spread = samples[(samples.size * 9) / 10] - samples[samples.size / 10],
        )
    }

    /**
     * How far the bubble reaches from one side of the lettering, in page pixels. Stops at the first band that is not
     * the bubble's colour, which is where the bubble's outline sits.
     */
    private fun walkOut(
        box: Box,
        stepX: Int,
        stepY: Int,
        reach: Float,
        background: Float,
        page: Page,
    ): Float {
        var distance = 0f
        while (distance < reach) {
            val next = distance + 1f
            val band = when {
                // The middle of the side, so a rounded corner doesn't end the walk early.
                stepX < 0 -> page.sample(box.left - next, box.centerY)
                stepX > 0 -> page.sample(box.right + next, box.centerY)
                stepY < 0 -> page.sample(box.centerX, box.top - next)
                else -> page.sample(box.centerX, box.bottom + next)
            }
            if (band < 0f || abs(band - background) > TOLERANCE) break
            distance = next
        }
        return when {
            stepX < 0 -> box.left - distance
            stepX > 0 -> box.right + distance
            stepY < 0 -> box.top - distance
            else -> box.bottom + distance
        }
    }

    /** How many of the plate's four corners are still the bubble's colour. */
    private fun cornersInside(plate: Box, background: Float, page: Page): Int {
        val insetX = plate.width * CORNER_INSET
        val insetY = plate.height * CORNER_INSET
        val corners = arrayOf(
            plate.left + insetX to plate.top + insetY,
            plate.right - insetX to plate.top + insetY,
            plate.left + insetX to plate.bottom - insetY,
            plate.right - insetX to plate.bottom - insetY,
        )
        return corners.count { (x, y) ->
            val value = page.sample(x, y)
            value >= 0f && abs(value - background) <= TOLERANCE
        }
    }

    /** The bubble's colour, as the median of what's inside the plate and outside the lettering. */
    private fun readColor(plate: Box, page: Page, color: PageColor): Int? {
        val thickness = (minOf(plate.width, plate.height) * RING).coerceIn(1f, 6f)
        val reds = ArrayList<Int>(16)
        val greens = ArrayList<Int>(16)
        val blues = ArrayList<Int>(16)
        for (step in 0 until STEPS) {
            val t = (step + 0.5f) / STEPS
            val x = (plate.left + plate.width * t).toInt()
            val y = (plate.top + plate.height * t).toInt()
            val points = arrayOf(
                x to (plate.top - thickness).toInt(),
                x to (plate.bottom + thickness).toInt(),
                (plate.left - thickness).toInt() to y,
                (plate.right + thickness).toInt() to y,
            )
            for ((px, py) in points) {
                if (page.sample(px.toFloat(), py.toFloat()) < 0f) continue
                val pixel = color.at(px, py)
                reds += (pixel shr 16) and 0xFF
                greens += (pixel shr 8) and 0xFF
                blues += pixel and 0xFF
            }
        }
        if (reds.isEmpty()) return null
        reds.sort()
        greens.sort()
        blues.sort()
        val middle = reds.size / 2
        return (0xFF shl 24) or (reds[middle] shl 16) or (greens[middle] shl 8) or blues[middle]
    }

    private const val STEPS = 4
    private const val RING = 0.16f
    private const val MAX_GROWTH = 1f
    private const val MAX_REACH = 48f
    private const val TOLERANCE = 0.14f
    private const val MAX_SPREAD = 0.30f
    private const val CORNER_INSET = 0.06f
    private const val STRAIGHT_CORNER = 0.06f
    private const val ROUNDED_CORNER = 0.22f
    private const val DARK_THRESHOLD = 0.45f
    private const val TRANSPARENT = 0x00000000
}
