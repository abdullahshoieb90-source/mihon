package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The reading of a long tap: which bubble, if any, is under the finger.
 *
 * A misplaced tap is the kind of bug that never shows in a build, so the reader's hit test is checked here, without a
 * device and without the page's own types.
 */
class TranslationBlockHitTest {

    private val first = DrawnBlock(left = 10f, top = 10f, right = 110f, bottom = 60f)
    private val second = DrawnBlock(left = 10f, top = 70f, right = 110f, bottom = 120f)

    @Test
    fun `a point inside a bubble finds that bubble`() {
        val drawn = listOf(first, second)
        assertEquals(0, drawn.indexAt(50f, 30f, slop = 0f))
        assertEquals(1, drawn.indexAt(100f, 110f, slop = 0f))
    }

    @Test
    fun `a point on the art finds nothing`() {
        val drawn = listOf(first, second)
        assertEquals(-1, drawn.indexAt(50f, 66f, slop = 0f))
        assertEquals(-1, drawn.indexAt(400f, 400f, slop = 0f))
    }

    @Test
    fun `a near miss still counts, because a finger is not a pixel`() {
        val drawn = listOf(first, second)
        // The first bubble ends at y = 60; a tap just below it is still meant for it.
        assertEquals(0, drawn.indexAt(50f, 64f, slop = 8f))
        // Without the slop, the same point falls between the two bubbles.
        assertEquals(-1, drawn.indexAt(65f, 65f, slop = 2f))
    }

    @Test
    fun `overlapping bubbles answer with the one drawn last`() {
        val overlapping = DrawnBlock(left = 40f, top = 20f, right = 140f, bottom = 80f)
        val drawn = listOf(first, overlapping)
        assertEquals(1, drawn.indexAt(60f, 30f, slop = 0f))
    }

    @Test
    fun `the edges of a bubble belong to it`() {
        assertEquals(0, listOf(first).indexAt(10f, 10f, slop = 0f))
        assertEquals(0, listOf(first).indexAt(110f, 60f, slop = 0f))
    }

    @Test
    fun `an empty page answers with nothing`() {
        assertEquals(-1, emptyList<DrawnBlock>().indexAt(10f, 10f, slop = 8f))
    }
}
