package tachiyomi.domain.translation.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/**
 * The shape a translation is painted over, read from the page around the lettering.
 *
 * A wrong reading shows up as a rectangle of flat colour over round art, which is a thing that ships quietly, so the
 * geometry is checked here instead of on a device.
 */
class BubblePlateTest {

    private val width = 300
    private val height = 220

    /** A page that is white inside [fill] and black everywhere else. */
    private fun page(fill: (Float, Float) -> Boolean): PageBrightness =
        PageBrightness { x, y -> if (fill(x.toFloat(), y.toFloat())) 1f else 0f }

    private fun detect(brightness: PageBrightness, box: FloatArray) = BubblePlateDetector.detect(
        textLeft = box[0],
        textTop = box[1],
        textRight = box[2],
        textBottom = box[3],
        width = width,
        height = height,
        brightness = brightness,
    )

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = floatArrayOf(left, top, right, bottom)

    @Test
    fun `a panel with straight corners is read as a box`() {
        val rectangle = page { x, y -> x in 100f..200f && y in 100f..160f }
        val plate = detect(rectangle, box(120f, 115f, 180f, 145f))!!

        assertEquals(PlateShape.RECT, plate.shape)
        // The walk stops where the panel does, not where the search distance does.
        assertEquals(100f, plate.left, 2f)
        assertEquals(200f, plate.right, 2f)
        assertEquals(100f, plate.top, 2f)
        assertEquals(160f, plate.bottom, 2f)
        // A box barely rounds its corners at all.
        assertTrue(plate.cornerRadius < plate.height * 0.1f)
    }

    @Test
    fun `an oval bubble is read as a stadium, not as the rectangle around it`() {
        val centerX = 150f
        val centerY = 130f
        val radiusX = 50f
        val radiusY = 30f
        val oval = page { x, y ->
            val dx = (x - centerX) / radiusX
            val dy = (y - centerY) / radiusY
            dx * dx + dy * dy <= 1f
        }
        val plate = detect(oval, box(130f, 118f, 170f, 142f))!!

        assertEquals(PlateShape.STADIUM, plate.shape)
        // The plate follows the oval instead of boxing it in.
        assertEquals(centerX - radiusX, plate.left, 3f)
        assertEquals(centerX + radiusX, plate.right, 3f)
        assertEquals(centerY - radiusY, plate.top, 3f)
        assertEquals(centerY + radiusY, plate.bottom, 3f)
        // Half the short side is what makes the ends half circles.
        assertEquals(plate.height / 2f, plate.cornerRadius, 0.01f)
    }

    @Test
    fun `the plate never runs away from the lettering`() {
        // The whole page is one colour, so nothing tells the walk where to stop: the search distance does.
        val endless = page { _, _ -> true }
        val plate = detect(endless, box(120f, 115f, 180f, 145f))!!
        // A search of 48 points in every direction, and not a pixel more.
        assertEquals(120f - 48f, plate.left, 1f)
        assertEquals(180f + 48f, plate.right, 1f)
        assertEquals(115f - 48f, plate.top, 1f)
        assertEquals(145f + 48f, plate.bottom, 1f)
    }

    @Test
    fun `art around the lettering leaves the block to the plain plate`() {
        // A striped background has no single colour to read, so no bubble is claimed.
        val striped = page { x, _ -> (x.roundToInt() / 3) % 2 == 0 }
        assertNull(detect(striped, box(120f, 115f, 180f, 145f)))
    }

    @Test
    fun `a dark bubble is marked dark, so the lettering turns light`() {
        val darkBubble = PageBrightness { x, y -> if (x in 100..200 && y in 100..160) 0.1f else 0.85f }
        val plate = detect(darkBubble, box(120f, 115f, 180f, 145f))!!
        assertTrue(plate.dark)
    }

    @Test
    fun `a light bubble is not marked dark`() {
        val lightBubble = page { x, y -> x in 100f..200f && y in 100f..160f }
        val plate = detect(lightBubble, box(120f, 115f, 180f, 145f))!!
        assertEquals(false, plate.dark)
    }

    @Test
    fun `a box with nothing to read around it is not claimed`() {
        assertNull(detect(PageBrightness { _, _ -> 1f }, box(120f, 115f, 120.5f, 145f)))
        assertNull(BubblePlateDetector.detect(1f, 2f, 3f, 4f, width = 0, height = 0, brightness = { _, _ -> 1f }))
    }

    @Test
    fun `the bubble colour is read from around the lettering, not from the letters`() {
        val greyBubble = PageBrightness { x, y -> if (x in 100..200 && y in 100..160) 0.5f else 0f }
        val color = PageColor { _, _ -> 0xFF808080.toInt() }
        val plate = BubblePlateDetector.detect(
            textLeft = 120f,
            textTop = 115f,
            textRight = 180f,
            textBottom = 145f,
            width = width,
            height = height,
            brightness = greyBubble,
            color = color,
        )!!
        assertEquals(0xFF808080.toInt(), plate.color)
    }
}
