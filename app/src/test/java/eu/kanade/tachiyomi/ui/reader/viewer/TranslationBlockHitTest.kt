package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Rect
import eu.kanade.tachiyomi.data.translation.TranslatedTextBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The reading of a long tap: which bubble, if any, is under the finger.
 */
class TranslationBlockHitTest {

    private val first = block("first", 10f, 10f, 110f, 60f)
    private val second = block("second", 10f, 70f, 110f, 120f)

    @Test
    fun `a point inside a bubble returns that bubble`() {
        val drawn = listOf(first, second)
        assertEquals(first.block, drawn.blockAt(50f, 30f, slop = 0f))
        assertEquals(second.block, drawn.blockAt(100f, 110f, slop = 0f))
    }

    @Test
    fun `a point on the art returns nothing`() {
        val drawn = listOf(first, second)
        assertNull(drawn.blockAt(50f, 66f, slop = 0f))
        assertNull(drawn.blockAt(400f, 400f, slop = 0f))
    }

    @Test
    fun `a near miss still counts, because a finger is not a pixel`() {
        val drawn = listOf(first, second)
        // The first bubble ends at y = 60; a tap a few points below it is still meant for it.
        assertEquals(first.block, drawn.blockAt(50f, 64f, slop = 8f))
        // Without the slop the same point falls between the two bubbles.
        assertNull(drawn.blockAt(65f, 65f, slop = 2f))
    }

    @Test
    fun `overlapping bubbles answer with the one drawn last`() {
        val overlapping = block("overlapping", 40f, 20f, 140f, 80f)
        val drawn = listOf(first, overlapping)
        assertEquals(overlapping.block, drawn.blockAt(60f, 30f, slop = 0f))
    }

    @Test
    fun `an empty page answers with nothing`() {
        assertNull(emptyList<DrawnBlock>().blockAt(10f, 10f, slop = 8f))
    }

    private fun block(text: String, left: Float, top: Float, right: Float, bottom: Float) = DrawnBlock(
        block = TranslatedTextBlock(
            box = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()),
            original = text,
            translated = text,
            lines = 1,
        ),
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )
}
