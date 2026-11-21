package tachiyomi.domain.translation.model

/**
 * Colour of the translated lettering. "Auto" follows the page: dark text on light panels, light text on dark ones.
 */
enum class TranslationTextColor {
    AUTO,
    WHITE,
    BLACK,
    RED,
    YELLOW,
    ;

    companion object {
        /** Argb value of the chosen colour, or null when the page should decide. */
        fun of(color: TranslationTextColor): Int? = when (color) {
            AUTO -> null
            WHITE -> 0xFFFFFFFF.toInt()
            BLACK -> 0xFF000000.toInt()
            RED -> 0xFFE53935.toInt()
            YELLOW -> 0xFFFFD600.toInt()
        }
    }
}

/**
 * Case of the translated lettering.
 */
enum class TranslationLetterCase {
    ORIGINAL,
    UPPER,
    LOWER,
    ;

    fun apply(text: String): String = when (this) {
        ORIGINAL -> text
        UPPER -> text.uppercase()
        LOWER -> text.lowercase()
    }
}

/**
 * How a translation is placed over the original lettering.
 *
 * [BUBBLE] reads the art around each block and paints over the bubble itself, oval or boxed, in the bubble's own
 * colour. [ROUNDED] keeps the plain rounded plate but grows it to the bubble, and [BOX] is the plain plate as it always
 * was — the escape hatch for art the reading gets wrong.
 */
enum class TranslationPlateStyle {
    BUBBLE,
    ROUNDED,
    BOX,
}

/**
 * What reads a page before it is translated.
 *
 * [RECOGNIZER] reads the lettering on the device and then translates the text: free, works with every provider, and
 * gets the words right. [VISION] sends the page itself to a model that can see it: it costs a request per page and
 * needs a key, and it understands a page the way a person does — who is speaking, the tone, and lettering no
 * on-device recognizer can read.
 */
enum class TranslationReadingEngine {
    RECOGNIZER,
    VISION,
}
