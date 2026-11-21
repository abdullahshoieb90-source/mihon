package tachiyomi.domain.translation.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * One line a reading model returned: the original lettering, where it sits on the page, and its translation.
 *
 * [left]..[bottom] are fractions of the page's width and height, not pixels: the model never sees the page at its real
 * size, so a fraction is the only measurement that survives.
 */
data class PageReadingLine(
    val original: String,
    val translated: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isValid: Boolean
        get() = original.length >= 2 && translated.length >= 2 &&
            right > left && bottom > top &&
            left >= -0.05f && top >= -0.05f && right <= 1.05f && bottom <= 1.05f
}

/**
 * Reads a vision model's answer to a page.
 *
 * Models are helpful, not obedient: the answer arrives wrapped in prose or a code fence, the numbers arrive as strings
 * with a percent sign, and a box arrives upside down because the corners were listed the other way round. All of that
 * is handled here rather than at the call site, because it is the difference between a translated page and none.
 */
object PageReadingParser {

    /**
     * The lines the model found, or an empty list when the answer held none.
     *
     * The shape asked for is an array of objects, but keys are matched loosely: a model that answers
     * `{"lines": [...]}` or names a field `translation` instead of `translated` is still understood.
     *
     * [pageWidth] and [pageHeight] are only a fallback: a model that answers in pixels instead of fractions can still
     * be understood when the page's size is known, and a line whose numbers make no sense either way is dropped rather
     * than painted over the art.
     */
    fun parse(answer: String, pageWidth: Int = 0, pageHeight: Int = 0): List<PageReadingLine> {
        val array = extractArray(answer) ?: return emptyList()
        return array.mapNotNull { element -> (element as? JsonObject)?.let { toLine(it, pageWidth, pageHeight) } }
            .filter { it.isValid }
    }

    /** The first JSON array in the text, whatever was written around it. */
    private fun extractArray(answer: String): JsonArray? {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching { Json.Default.parseToJsonElement(answer.substring(start, end + 1)).jsonArray }
            .getOrNull()
    }

    private fun toLine(element: JsonObject, pageWidth: Int, pageHeight: Int): PageReadingLine? {
        val original = element.text(ORIGINAL_KEYS) ?: return null
        val translated = element.text(TRANSLATED_KEYS) ?: return null

        val left = element.coordinate("left", "x") ?: return null
        val top = element.coordinate("top", "y") ?: return null
        val right = element.coordinate("right") ?: left.sized(element.coordinate("width"))
        val bottom = element.coordinate("bottom") ?: top.sized(element.coordinate("height"))
        if (right == null || bottom == null) return null

        val horizontal = normalised(left, right, pageWidth) ?: return null
        val vertical = normalised(top, bottom, pageHeight) ?: return null
        return PageReadingLine(
            original = original,
            translated = translated,
            left = horizontal.first,
            top = vertical.first,
            right = horizontal.second,
            bottom = vertical.second,
        )
    }

    /**
     * One axis of a box, in fractions of the page.
     *
     * A model gives either fractions, percentages or pixels, and the same answer can mix them. A number with a percent
     * sign is a percentage; a number no bigger than the page is already a fraction; anything else is pixels, which only
     * means something once the page's size is known — so without it the line is refused instead of guessed at.
     */
    private fun normalised(start: Coordinate, end: Coordinate, pageSize: Int): Pair<Float, Float>? {
        val first = axisValue(start, pageSize) ?: return null
        val second = axisValue(end, pageSize) ?: return null
        return minOf(first, second) to maxOf(first, second)
    }

    private fun axisValue(coordinate: Coordinate, pageSize: Int): Float? {
        val value = coordinate.value
        if (coordinate.percent) return value / 100f
        if (value <= 1.05f) return value
        if (pageSize <= 0) return null
        return value / pageSize
    }

    private fun JsonObject.text(keys: List<String>): String? = keys
        .firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.content?.trim() }
        ?.takeIf { it.length >= 2 }

    /** A corner, whether it arrived as a number or as a string with a percent sign or a comma. */
    private fun JsonObject.coordinate(vararg keys: String): Coordinate? {
        for (key in keys) {
            val raw = (this[key] as? JsonPrimitive)?.content?.trim() ?: continue
            val percent = raw.endsWith("%")
            val cleaned = raw.removeSuffix("%").replace(',', '.').trim()
            cleaned.toFloatOrNull()?.let { return Coordinate(it, percent) }
        }
        return null
    }

    /** A size next to a corner: the other edge is the corner plus this. */
    private fun Coordinate.sized(size: Coordinate?): Coordinate? =
        size?.let { Coordinate(value + it.value, it.percent) }

    private data class Coordinate(val value: Float, val percent: Boolean)

    private val ORIGINAL_KEYS = listOf("original", "source", "text", "japanese", "raw")
    private val TRANSLATED_KEYS = listOf("translated", "translation", "target", "arabic", "result")
}
