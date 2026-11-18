package tachiyomi.domain.translation.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The reading of a long tap: which bubble, if any, is under the finger.
 *
 * A misplaced tap is the kind of bug that never shows in a build, so the geometry is checked here.
 */
class TranslatedBlockAreaTest {

    private val first = TranslatedBlockArea(left = 10f, top = 10f, right = 110f, bottom = 60f)
    private val second = TranslatedBlockArea(left = 10f, top = 70f, right = 110f, bottom = 120f)

    @Test
    fun `a point inside a bubble finds that bubble`() {
        val areas = listOf(first, second)
        assertEquals(0, areas.indexAt(50f, 30f, slop = 0f))
        assertEquals(1, areas.indexAt(100f, 110f, slop = 0f))
    }

    @Test
    fun `a point on the art finds nothing`() {
        val areas = listOf(first, second)
        assertEquals(-1, areas.indexAt(50f, 66f, slop = 0f))
        assertEquals(-1, areas.indexAt(400f, 400f, slop = 0f))
    }

    @Test
    fun `a near miss still counts, because a finger is not a pixel`() {
        val areas = listOf(first, second)
        // The first bubble ends at y = 60; a tap just below it is still meant for it.
        assertEquals(0, areas.indexAt(50f, 64f, slop = 8f))
        // Without the slop the same point falls between the two bubbles.
        assertEquals(-1, areas.indexAt(65f, 65f, slop = 2f))
    }

    @Test
    fun `overlapping bubbles answer with the one drawn last`() {
        val overlapping = TranslatedBlockArea(left = 40f, top = 20f, right = 140f, bottom = 80f)
        assertEquals(1, listOf(first, overlapping).indexAt(60f, 30f, slop = 0f))
    }

    @Test
    fun `the edges of a bubble belong to it`() {
        assertEquals(0, listOf(first).indexAt(10f, 10f, slop = 0f))
        assertEquals(0, listOf(first).indexAt(110f, 60f, slop = 0f))
    }

    @Test
    fun `an empty page answers with nothing`() {
        assertEquals(-1, emptyList<TranslatedBlockArea>().indexAt(10f, 10f, slop = 8f))
    }
}
