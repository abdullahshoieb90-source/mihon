package tachiyomi.domain.translation.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading a vision model's answer.
 *
 * The model is asked for a clean JSON array and returns something close to it: a code fence, a sentence first, a
 * percent sign on a coordinate, or a box whose corners are listed backwards. Each of those used to be a blank page, so
 * each of them is a test here.
 */
class PageReadingParserTest {

    @Test
    fun `a clean answer is read as lines`() {
        val answer = """
            [
              {"original": "Hello there", "translated": "أهلًا", "left": 0.1, "top": 0.2,
               "right": 0.5, "bottom": 0.3},
              {"original": "Goodbye", "translated": "وداعًا", "left": 0.6, "top": 0.7,
               "right": 0.9, "bottom": 0.8}
            ]
        """.trimIndent()
        val lines = PageReadingParser.parse(answer)

        assertEquals(2, lines.size)
        assertEquals("Hello there", lines[0].original)
        assertEquals("أهلًا", lines[0].translated)
        assertEquals(0.1f, lines[0].left)
        assertEquals(0.3f, lines[0].bottom)
    }

    @Test
    fun `an answer wrapped in a code fence and a sentence still parses`() {
        val answer = """
            Sure! Here is the page:
            ```json
            [{"original": "Wait", "translated": "استنى", "left": 0.2, "top": 0.2,
              "right": 0.4, "bottom": 0.3}]
            ```
            Let me know if you need anything else.
        """.trimIndent()
        assertEquals(1, PageReadingParser.parse(answer).size)
    }

    @Test
    fun `coordinates written as percentages or strings are understood`() {
        val answer = """
            [{"original": "Run", "translated": "اجري", "left": "10%", "top": "0,2",
              "right": "50%", "bottom": "0.4"}]
        """.trimIndent()
        val line = PageReadingParser.parse(answer).single()

        assertEquals(0.1f, line.left, 0.01f)
        assertEquals(0.2f, line.top, 0.01f)
        assertEquals(0.5f, line.right, 0.01f)
    }

    @Test
    fun `a box given upside down is turned the right way round`() {
        val answer = """
            [{"original": "No way", "translated": "مستحيل", "left": 0.6, "top": 0.8,
              "right": 0.2, "bottom": 0.3}]
        """.trimIndent()
        val line = PageReadingParser.parse(answer).single()

        assertEquals(0.2f, line.left)
        assertEquals(0.3f, line.top)
        assertEquals(0.6f, line.right)
        assertEquals(0.8f, line.bottom)
    }

    @Test
    fun `a box given as a corner and a size is read as a box`() {
        val answer = """
            [{"original": "Look", "translated": "بص", "x": 0.3, "y": 0.4,
              "width": 0.2, "height": 0.1}]
        """.trimIndent()
        val line = PageReadingParser.parse(answer).single()

        assertEquals(0.3f, line.left, 0.01f)
        assertEquals(0.5f, line.right, 0.01f)
        assertEquals(0.5f, line.bottom, 0.01f)
    }

    @Test
    fun `other names for the same two fields are accepted`() {
        val answer = """
            [{"text": "Who are you", "translation": "مين انت", "left": 0.1, "top": 0.1,
              "right": 0.4, "bottom": 0.2}]
        """.trimIndent()
        val line = PageReadingParser.parse(answer).single()

        assertEquals("Who are you", line.original)
        assertEquals("مين انت", line.translated)
    }

    @Test
    fun `a line without a translation or without text is dropped`() {
        val answer = """
            [
              {"original": "Hello", "left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.2},
              {"translated": "أهلًا", "left": 0.1, "top": 0.3, "right": 0.3, "bottom": 0.4},
              {"original": "Hi", "translated": "هاي", "left": 0.1, "top": 0.5, "right": 0.3, "bottom": 0.6}
            ]
        """.trimIndent()
        assertEquals(1, PageReadingParser.parse(answer).size)
    }

    @Test
    fun `a box far outside the page is dropped, so the art isn't covered`() {
        val answer = """
            [
              {"original": "Off the page", "translated": "بره", "left": 2.0, "top": 0.1,
               "right": 3.0, "bottom": 0.2},
              {"original": "On it", "translated": "جوّه", "left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.2}
            ]
        """.trimIndent()
        assertEquals(1, PageReadingParser.parse(answer).size)
    }

    @Test
    fun `a box given in pixels is understood once the page size is known`() {
        val answer = """
            [{"original": "Hey", "translated": "هاي", "left": 100, "top": 200,
              "right": 300, "bottom": 260}]
        """.trimIndent()
        val line = PageReadingParser.parse(answer, pageWidth = 1000, pageHeight = 2000).single()

        assertEquals(0.1f, line.left, 0.01f)
        assertEquals(0.1f, line.top, 0.01f)
        assertEquals(0.3f, line.right, 0.01f)
        assertEquals(0.13f, line.bottom, 0.01f)
    }

    @Test
    fun `a pixel box is dropped when the page size is unknown, not guessed at`() {
        val answer = """
            [{"original": "Hey", "translated": "هاي", "left": 100, "top": 200,
              "right": 300, "bottom": 260}]
        """.trimIndent()
        assertTrue(PageReadingParser.parse(answer).isEmpty())
    }

    @Test
    fun `an answer with no array at all is an empty reading, not a crash`() {
        assertTrue(PageReadingParser.parse("I can't see any text on this page.").isEmpty())
        assertTrue(PageReadingParser.parse("").isEmpty())
        assertTrue(PageReadingParser.parse("[ not json").isEmpty())
    }

    @Test
    fun `a nested shape with a lines array is still read`() {
        val answer = """
            {"lines": [{"original": "Hm", "translated": "همم", "left": 0.1, "top": 0.1,
                        "right": 0.2, "bottom": 0.2}]}
        """.trimIndent()
        // The bracket cut finds the inner array, which is the one that matters.
        assertEquals(1, PageReadingParser.parse(answer).size)
    }
}
