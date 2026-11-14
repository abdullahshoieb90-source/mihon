package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.OcrScript
import tachiyomi.domain.translation.model.TranslationLanguage
import tachiyomi.domain.translation.model.TranslationProvider
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.File
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class TranslatedTextBlock(
    val box: Rect,
    val original: String,
    val translated: String,
)

/**
 * One page worth of translations.
 *
 * Only a thumbnail of the page is kept, not the page itself: the reader draws the art, and the thumbnail is enough
 * to size the text and to pick a plate colour. That keeps a whole chapter cached in memory instead of a few pages.
 */
class TranslatedPage(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val preview: Bitmap?,
    val previewScale: Float,
    val blocks: List<TranslatedTextBlock>,
    val source: TranslationLanguage,
    val target: TranslationLanguage,
)

@Inject
@SingleIn(AppScope::class)
class PageTranslator(
    private val context: Context,
    private val translator: Translator,
    private val ocrEngine: OcrEngine,
    private val preferences: TranslationPreferences,
) {

    suspend fun translate(
        pageIndex: Int,
        openStream: (() -> InputStream)?,
    ): TranslatedPage = withContext(Dispatchers.IO) {
        val open = openStream ?: throw TranslationException(TranslationError.PAGE_NOT_AVAILABLE)
        val source = translator.sourceLanguage
        val target = translator.targetLanguage

        val file = cacheFile(pageIndex)
        try {
            open().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            file.setReadOnly()

            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val decoder = try {
                BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                    ?: throw TranslationException(TranslationError.PAGE_NOT_AVAILABLE)
            } catch (e: Exception) {
                descriptor.close()
                throw TranslationException(TranslationError.PAGE_NOT_AVAILABLE)
            }

            try {
                val nativeWidth = decoder.width
                val nativeHeight = decoder.height
                if (nativeWidth <= 0 || nativeHeight <= 0) {
                    throw TranslationException(TranslationError.PAGE_NOT_AVAILABLE)
                }

                val displayWidth = decodeDisplayWidth(file, nativeWidth)
                if (displayWidth <= 0) throw TranslationException(TranslationError.PAGE_NOT_AVAILABLE)
                val displayHeight = displayHeight(nativeWidth, nativeHeight, displayWidth)
                val preview = decodePreview(file, displayWidth)
                // Dots of an ellipsis, page numbers and sound effects are recognized as "0000" or "…" all the
                // time; translating those only paints boxes over the art.
                val recognition = recognize(decoder, nativeWidth, nativeHeight, source)
                val blocks = recognition.blocks
                    .filter { it.text.count { char -> char.isLetter() } >= MIN_LETTERS }
                    .filterNot { it.text.isSoundEffect() }
                if (blocks.isEmpty()) throw TranslationException(TranslationError.NO_TEXT_FOUND)

                // Text positions are read at the OCR scale and have to be drawn on the displayed bitmap.
                val ratio = displayWidth.toFloat() / nativeWidth
                // Line breaks and repeated spacing from the recognizer break the sentences the service sees.
                val cleaned = blocks.map { it.text.normalizeForTranslation() }
                // The on-device models need the real source language, and the recognizer already told us which
                // script it just read, so "detect automatically" is resolved here instead of failing.
                val effectiveSource = if (
                    source == TranslationLanguage.AUTO &&
                    preferences.provider.get() == TranslationProvider.ON_DEVICE
                ) {
                    recognition.script.defaultLanguage()
                } else {
                    source
                }
                val translation = translateAll(cleaned, effectiveSource, target)
                val translatedBlocks = blocks.mapIndexedNotNull { index, block ->
                    val translated = translation.getOrNull(index)?.trim()
                    val hasLetters = translated != null && translated.any { it.isLetter() }
                    if (translated.isNullOrEmpty() || !hasLetters) {
                        null
                    } else {
                        TranslatedTextBlock(
                            box = block.box.scaled(ratio),
                            original = cleaned[index],
                            translated = translated,
                        )
                    }
                }
                if (translatedBlocks.isEmpty()) throw TranslationException(TranslationError.NO_TRANSLATION)

                TranslatedPage(
                    pageIndex = pageIndex,
                    width = displayWidth,
                    height = displayHeight,
                    preview = preview,
                    previewScale = preview?.let { it.width.toFloat() / displayWidth } ?: 1f,
                    blocks = translatedBlocks,
                    source = source,
                    target = target,
                )
            } finally {
                decoder.recycle()
                descriptor.close()
            }
        } finally {
            file.delete()
        }
    }

    private fun cacheFile(pageIndex: Int): File {
        val dir = File(context.cacheDir, "translation").apply { mkdirs() }
        return File(dir, "page-$pageIndex-${System.nanoTime()}.img")
    }

    /**
     * The size the reader shows the page at. Only the dimensions are decoded: the art belongs to the reader, so a
     * second copy for every translated page would fill memory for nothing.
     */
    private fun decodeDisplayWidth(file: File, nativeWidth: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sampleSize = 1
        while ((nativeWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize) > DISPLAY_PIXEL_BUDGET) {
            sampleSize *= 2
        }
        return nativeWidth / sampleSize
    }

    private fun displayHeight(nativeWidth: Int, nativeHeight: Int, displayWidth: Int): Int {
        if (nativeWidth <= 0) return nativeHeight
        return (nativeHeight.toLong() * displayWidth / nativeWidth).toInt().coerceAtLeast(1)
    }

    /**
     * A thumbnail wide enough to tell a white bubble from a dark panel, which is all the text layer needs.
     */
    private fun decodePreview(file: File, displayWidth: Int): Bitmap? {
        var sampleSize = 1
        while (displayWidth / sampleSize > PREVIEW_WIDTH) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inScaled = false
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private suspend fun recognize(
        decoder: BitmapRegionDecoder,
        nativeWidth: Int,
        nativeHeight: Int,
        source: TranslationLanguage,
    ): Recognition {
        val primary = source.ocrScript ?: run {
            if (source != TranslationLanguage.AUTO) {
                throw TranslationException(TranslationError.UNSUPPORTED_SOURCE_SCRIPT)
            }
            detectScript(decoder, nativeWidth, nativeHeight)
        }

        var script = primary
        val sampleSize = ocrSampleSize(nativeWidth)
        var blocks = readPage(decoder, nativeWidth, nativeHeight, script, sampleSize)

        // A page can mix scripts (Japanese sound effects on an English page, English signage on a Japanese one),
        // and a wrong guess reads nothing at all. The other common model gets a pass too and its findings are
        // folded in wherever the first pass had nothing.
        if (source == TranslationLanguage.AUTO) {
            val secondary = if (script == OcrScript.LATIN) OcrScript.JAPANESE else OcrScript.LATIN
            val extra = readPage(decoder, nativeWidth, nativeHeight, secondary, sampleSize)
            if (blocks.size < MIN_BLOCKS_BEFORE_RETRY || extra.isNotEmpty()) {
                blocks = mergeReadings(blocks, extra)
            }
            // Still nothing usable: the guess itself was wrong, so the remaining models are tried in turn.
            if (blocks.size < MIN_BLOCKS_BEFORE_RETRY) {
                for (candidate in OcrScript.entries) {
                    if (candidate == script || candidate == secondary) continue
                    val attempt = readPage(decoder, nativeWidth, nativeHeight, candidate, sampleSize)
                    if (attempt.size > blocks.size) {
                        blocks = attempt
                        script = candidate
                    }
                    if (blocks.size >= MIN_BLOCKS_BEFORE_RETRY) break
                }
            }
        }

        blocks = enrichSparseBlocks(blocks, decoder, script, nativeWidth, nativeHeight, sampleSize)

        return Recognition(blocks, script)
    }

    private fun ocrSampleSize(nativeWidth: Int): Int {
        val wantedWidth = when (preferences.ocrQuality.get()) {
            1 -> TranslationPreferences.OCR_FAST_WIDTH
            3 -> TranslationPreferences.OCR_ACCURATE_WIDTH
            else -> TranslationPreferences.OCR_BALANCED_WIDTH
        }
        return max(1, ceil(nativeWidth / wantedWidth.toFloat()).toInt())
    }

    private suspend fun readPage(
        decoder: BitmapRegionDecoder,
        nativeWidth: Int,
        nativeHeight: Int,
        script: OcrScript,
        sampleSize: Int,
    ): List<OcrBlock> {
        val width = nativeWidth / sampleSize
        val height = nativeHeight / sampleSize

        return ocrEngine.recognizeTiles(width, height, script) { tile ->
            val region = Rect(
                tile.left * sampleSize,
                tile.top * sampleSize,
                tile.right * sampleSize,
                tile.bottom * sampleSize,
            )
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
            }
            decoder.decodeRegion(region, options)?.let { OcrTile(it, release = true) }
        }
    }

    /**
     * Keeps the first reading and adds whatever the second script found in places the first one left empty.
     */
    private fun mergeReadings(primary: List<OcrBlock>, extra: List<OcrBlock>): List<OcrBlock> {
        if (extra.isEmpty()) return primary
        val merged = primary.toMutableList()
        extra.forEach { candidate ->
            val overlaps = merged.any { existing -> overlapRatio(existing.box, candidate.box) >= 0.3f }
            if (!overlaps) merged += candidate
        }
        return merged.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left).toLong() * (bottom - top)
        val smaller = min(a.width().toLong() * a.height(), b.width().toLong() * b.height()).coerceAtLeast(1)
        return inter.toFloat() / smaller
    }

    /**
     * A big bubble that came back with a word or two was read while something crossed it (a spiky border, a hand,
     * another line of art). Those boxes are read again at native resolution, and the richer reading wins.
     */
    private suspend fun enrichSparseBlocks(
        blocks: List<OcrBlock>,
        decoder: BitmapRegionDecoder,
        script: OcrScript,
        nativeWidth: Int,
        nativeHeight: Int,
        sampleSize: Int,
    ): List<OcrBlock> {
        if (blocks.size < 2) return blocks

        val areas = blocks.map { it.box.width().toLong() * it.box.height() }.sorted()
        val median = areas[areas.size / 2]
        if (median <= 0) return blocks

        val suspects = blocks
            .filter { it.text.length <= SPARSE_TEXT_LENGTH && it.box.width().toLong() * it.box.height() > median * 3 }
            .sortedByDescending { it.box.width().toLong() * it.box.height() }
            .take(MAX_ENRICHED_BLOCKS)

        if (suspects.isEmpty()) return blocks

        return blocks.map { block ->
            if (suspects.none { it === block }) return@map block

            val region = Rect(
                (block.box.left * sampleSize).coerceIn(0, nativeWidth - 1),
                (block.box.top * sampleSize).coerceIn(0, nativeHeight - 1),
                (block.box.right * sampleSize).coerceIn(1, nativeWidth),
                (block.box.bottom * sampleSize).coerceIn(1, nativeHeight),
            )
            if (region.width() < 8 || region.height() < 8) return@map block

            val bitmap = runCatching {
                decoder.decodeRegion(region, BitmapFactory.Options().apply { inScaled = false })
            }.getOrNull() ?: return@map block

            try {
                val better = ocrEngine.recognize(bitmap, script)
                    .joinToString(separator = " ") { it.text }
                    .trim()
                if (better.length > block.text.length) OcrBlock(better, block.box) else block
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * "Auto" is the one source that has to guess, so a couple of slices are read first and the script the page is
     * actually written in is used for the real pass. Assuming Japanese used to leave English pages untranslated.
     */
    private suspend fun detectScript(
        decoder: BitmapRegionDecoder,
        nativeWidth: Int,
        nativeHeight: Int,
    ): OcrScript {
        val sampleSize = max(1, ceil(nativeWidth / DETECTION_WIDTH.toFloat()).toInt())
        val sliceHeight = min(nativeHeight, DETECTION_SLICE_HEIGHT * sampleSize)
        val starts = if (nativeHeight <= sliceHeight) {
            listOf(0)
        } else {
            listOf(0, (nativeHeight - sliceHeight) / 2, nativeHeight - sliceHeight)
        }

        var cjk = 0
        var hangul = 0
        var latin = 0
        starts.distinct().forEach { top ->
            val region = Rect(0, top, nativeWidth, top + sliceHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
            }
            val sample = decoder.decodeRegion(region, options) ?: return@forEach
            try {
                ocrEngine.recognize(sample, OcrScript.JAPANESE).forEach { block ->
                    block.text.forEach { char ->
                        when {
                            char in '\uAC00'..'\uD7AF' -> hangul++
                            char.isCjk() -> cjk++
                            char in 'a'..'z' || char in 'A'..'Z' -> latin++
                        }
                    }
                }
            } finally {
                sample.recycle()
            }
        }

        return when {
            hangul > cjk + latin -> OcrScript.KOREAN
            cjk >= latin -> OcrScript.JAPANESE
            else -> OcrScript.LATIN
        }
    }

    /**
     * Translates every block, but doesn't let one bad response cost the whole page — anything that fails is simply
     * left untranslated instead of aborting the page.
     */
    private suspend fun translateAll(
        texts: List<String>,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): List<String?> {
        return try {
            translator.translate(texts, source, target)
        } catch (e: TranslationException) {
            if (texts.size <= 1) throw e

            // If the first block fails as well the provider is unreachable, so there's nothing to salvage.
            val first = runCatching { translator.translate(listOf(texts.first()), source, target).first() }
                .getOrElse { throw e }
            listOf(first) + texts.drop(1).map { text ->
                runCatching { translator.translate(listOf(text), source, target).first() }.getOrNull()
            }
        }
    }

    /**
     * Recognized lettering arrives with artificial line breaks and repeated spacing, which translation services turn
     * into broken sentences.
     */
    private fun String.normalizeForTranslation(): String = this
        // Control characters and the box-drawing bars the recognizer likes to add around lettering.
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex("[\\u2500-\\u257F|｜│¦_~^`]+"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\.{2,}|…{2,}"), "…")
        .replace(Regex("([!?！？])\\1+"), "$1")
        .trim(' ', '-', '_', '.', ',', '·', '|', '~', '…', '「', '」', '『', '』')

    /**
     * Sound effects are drawn in fat capitals ("GUD", "STEP") and translating them only paints nonsense over the art.
     * Dialogue never looks like that, so short all-capital Latin blocks are left alone.
     */
    private fun String.isSoundEffect(): Boolean {
        val letters = filter { it.isLetter() }
        if (letters.length !in 3..6) return false
        if (letters.any { it.code > 0x2FF }) return false
        // "HAA…", "NO", "AH" are moans and shouts from the dialogue, not lettering drawn over the art.
        if (letters.lowercase() in DIALOGUE_SHOUTS) return false
        return letters.all { it.isUpperCase() }
    }

    /** The language a script is normally written in, used when the reader left the source on automatic. */
    private fun OcrScript.defaultLanguage(): TranslationLanguage = when (this) {
        OcrScript.JAPANESE -> TranslationLanguage.JAPANESE
        OcrScript.KOREAN -> TranslationLanguage.KOREAN
        OcrScript.CHINESE -> TranslationLanguage.CHINESE_SIMPLIFIED
        OcrScript.LATIN -> TranslationLanguage.ENGLISH
    }

    /** What the recognizer read and the script it read it in. */
    private data class Recognition(
        val blocks: List<OcrBlock>,
        val script: OcrScript,
    )

    private fun Rect.scaled(ratio: Float): Rect = Rect(
        (left * ratio).toInt(),
        (top * ratio).toInt(),
        (right * ratio).toInt(),
        (bottom * ratio).toInt(),
    )

    private fun Char.isCjk(): Boolean = this in '\u3040'..'\u30FF' || this in '\u3400'..'\u9FFF'

    companion object {
        /** Blocks with fewer letters are ellipsis dots, page numbers or noise, not dialogue. */
        private const val MIN_LETTERS = 2

        private val DIALOGUE_SHOUTS = setOf(
            "ah", "ahh", "aah", "ha", "haa", "hah", "oh", "ooh", "no", "yes", "hm", "hmm", "mm", "uh", "eh",
            "hey", "ow", "ouch", "wow", "gah", "gya", "kya", "nya", "ugh", "argh",
        )

        private const val DISPLAY_PIXEL_BUDGET = 8_000_000L
        private const val PREVIEW_WIDTH = 160

        /** Fewer readable blocks than this usually means the wrong script was guessed. */
        private const val MIN_BLOCKS_BEFORE_RETRY = 3

        /** A block with this little text inside a box three times the median is a failed reading. */
        private const val SPARSE_TEXT_LENGTH = 12
        private const val MAX_ENRICHED_BLOCKS = 3
        private const val DETECTION_SLICE_HEIGHT = 900
        private const val DETECTION_WIDTH = 720
    }
}
