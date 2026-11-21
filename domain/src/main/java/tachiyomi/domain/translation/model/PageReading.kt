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
     */
    fun parse(answer: String): List<PageReadingLine> {
        val array = extractArray(answer) ?: return emptyList()
        return array.mapNotNull { element -> (element as? JsonObject)?.let(::toLine) }
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

    private fun toLine(element: JsonObject): PageReadingLine? {
        val original = element.text(ORIGINAL_KEYS) ?: return null
        val translated = element.text(TRANSLATED_KEYS) ?: return null

        val left = element.number("left", "x") ?: 0f
        val top = element.number("top", "y") ?: 0f
        val right = element.number("right") ?: (left + (element.number("width") ?: 0f))
        val bottom = element.number("bottom") ?: (top + (element.number("height") ?: 0f))

        val box = normalised(left, top, right, bottom)
        return PageReadingLine(
            original = original,
            translated = translated,
            left = box[0],
            top = box[1],
            right = box[2],
            bottom = box[3],
        )
    }

    /**
     * A box the model gave as `x, y, width, height` sized to the whole page means "I don't know where this is", and
     * drawing it over the page would cover the art. Those lines are dropped instead.
     */
    private fun normalised(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val box = floatArrayOf(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
        // Percentages arrive as 0..100 now and then.
        if (box[2] > 1.6f || box[3] > 1.6f) {
            for (index in box.indices) box[index] /= 100f
        }
        return box
    }

    private fun JsonObject.text(keys: List<String>): String? = keys
        .firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.content?.trim() }
        ?.takeIf { it.length >= 2 }

    /** A coordinate, whether it arrived as a number or as a string with a percent sign or a comma. */
    private fun JsonObject.number(vararg keys: String): Float? {
        for (key in keys) {
            val raw = (this[key] as? JsonPrimitive)?.content?.trim() ?: continue
            val cleaned = raw.replace(',', '.').removeSuffix("%").trim()
            cleaned.toFloatOrNull()?.let { return it }
        }
        return null
    }

    private val ORIGINAL_KEYS = listOf("original", "source", "text", "japanese", "raw")
    private val TRANSLATED_KEYS = listOf("translated", "translation", "target", "arabic", "result")
}
