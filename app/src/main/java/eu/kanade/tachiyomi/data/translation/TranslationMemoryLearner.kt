package eu.kanade.tachiyomi.data.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.OcrScript
import tachiyomi.domain.translation.model.TranslationLanguage
import tachiyomi.domain.translation.service.TranslationPreferences
import okhttp3.Request
import kotlin.math.max
import kotlin.math.min

sealed interface TranslationLearningState {
    data object Idle : TranslationLearningState
    data class Running(val chapter: Int, val chapters: Int, val pairs: Int) : TranslationLearningState
    data class Done(val chapters: Int, val pairs: Int, val total: Int) : TranslationLearningState
    data class Failed(val message: String) : TranslationLearningState
}

/**
 * Learns how a series is already translated by reading a translated copy of it next to the raw one.
 *
 * Both versions show the same art, so a bubble sits in the same place on both pages: the raw text and the translated
 * text of every bubble are read with the recognizer and paired by position. The pairs then teach the translator the
 * names, honorifics and phrasing this series uses.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationMemoryLearner(
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val ocrEngine: OcrEngine,
    private val store: TranslationMemoryStore,
    private val preferences: TranslationPreferences,
    private val networkHelper: NetworkHelper,
) {

    private val mutableState = MutableStateFlow<TranslationLearningState>(TranslationLearningState.Idle)
    val state: StateFlow<TranslationLearningState> = mutableState.asStateFlow()

    fun reset() {
        mutableState.value = TranslationLearningState.Idle
    }

    /**
     * Reads every chapter the two entries share and stores what it learned under [rawMangaId], which is the entry the
     * user reads the raw chapters from.
     */
    suspend fun learn(rawMangaId: Long, translatedMangaId: Long) = withContext(Dispatchers.IO) {
        mutableState.value = TranslationLearningState.Running(chapter = 0, chapters = 0, pairs = 0)
        try {
            val rawManga = getManga.await(rawMangaId)
                ?: return@withContext fail("Manga not found")
            val translatedManga = getManga.await(translatedMangaId)
                ?: return@withContext fail("Translated manga not found")

            val rawSource = sourceManager.getOrStub(rawManga.source) as? HttpSource
                ?: return@withContext fail(NO_SOURCE)
            val translatedSource = sourceManager.getOrStub(translatedManga.source) as? HttpSource
                ?: return@withContext fail(NO_SOURCE)

            val rawChapters = getChaptersByMangaId.await(rawMangaId).readable()
            val translatedChapters = getChaptersByMangaId.await(translatedMangaId).readable()
            val pairs = matchChapters(rawChapters, translatedChapters)
            if (pairs.isEmpty()) return@withContext fail(NO_SHARED_CHAPTERS)

            var learned = 0
            var done = 0
            var script: OcrScript? = null

            pairs.forEach { (raw, translated) ->
                mutableState.value = TranslationLearningState.Running(done, pairs.size, learned)
                val pairsOfChapter = readChapterPair(
                    rawSource = rawSource,
                    translatedSource = translatedSource,
                    raw = raw,
                    translated = translated,
                    script = script,
                )
                script = pairsOfChapter.script ?: script
                if (pairsOfChapter.pairs.isNotEmpty()) {
                    learned = store.remember(rawMangaId, pairsOfChapter.pairs)
                }
                done++
                mutableState.value = TranslationLearningState.Running(done, pairs.size, learned)
            }

            mutableState.value = TranslationLearningState.Done(
                chapters = done,
                pairs = learned,
                total = store.count(rawMangaId),
            )
        } catch (e: CancellationException) {
            mutableState.value = TranslationLearningState.Idle
            throw e
        } catch (e: Exception) {
            fail(e.message ?: UNKNOWN)
        }
    }

    private suspend fun fail(message: String) {
        mutableState.value = TranslationLearningState.Failed(message)
    }

    private class ChapterReading(
        val pairs: List<LearnedPair>,
        val script: OcrScript?,
    )

    private suspend fun readChapterPair(
        rawSource: HttpSource,
        translatedSource: HttpSource,
        raw: Chapter,
        translated: Chapter,
        script: OcrScript?,
    ): ChapterReading {
        val rawPages = runCatching { rawSource.getPageList(raw.toSChapter()) }.getOrNull().orEmpty()
        val translatedPages = runCatching { translatedSource.getPageList(translated.toSChapter()) }
            .getOrNull()
            .orEmpty()
        val count = min(rawPages.size, translatedPages.size).coerceAtMost(MAX_PAGES_PER_CHAPTER)
        if (count == 0) return ChapterReading(emptyList(), null)

        var usedScript = script
        val pairs = mutableListOf<LearnedPair>()
        for (index in 0 until count) {
            val rawBitmap = fetch(rawSource, rawPages[index]) ?: continue
            try {
                if (usedScript == null) usedScript = detectScript(rawBitmap)
                val rawBlocks = ocrEngine.recognize(rawBitmap, usedScript)
                    .filter { it.text.count { char -> char.isLetter() } >= MIN_LETTERS }
                if (rawBlocks.isEmpty()) continue

                val translatedBitmap = fetch(translatedSource, translatedPages[index]) ?: continue
                try {
                    val translatedBlocks = ocrEngine.recognize(translatedBitmap, OcrScript.ARABIC)
                        .filter { it.text.count { char -> char.isLetter() } >= MIN_LETTERS }
                    if (translatedBlocks.isEmpty()) continue
                    pairs += align(rawBlocks, translatedBlocks)
                } finally {
                    translatedBitmap.recycle()
                }
            } finally {
                rawBitmap.recycle()
            }
        }
        return ChapterReading(pairs, usedScript)
    }

    /**
     * Pairs each raw bubble with the translated bubble drawn in the same place. The pages are the same artwork, so a
     * bubble that overlaps its counterpart heavily is its translation.
     */
    private fun align(raw: List<OcrBlock>, translated: List<OcrBlock>): List<LearnedPair> {
        val used = mutableSetOf<Int>()
        val pairs = mutableListOf<LearnedPair>()
        raw.forEach { source ->
            val best = translated.indices
                .filter { it !in used }
                .map { index -> index to overlap(source.box, translated[index].box) }
                .maxByOrNull { it.second }
            if (best == null || best.second < MIN_OVERLAP) return@forEach
            used += best.first
            val target = translated[best.first].text.trim()
            val origin = source.text.trim()
            if (target.isEmpty() || origin.isEmpty()) return@forEach
            pairs += LearnedPair(origin, target)
        }
        return pairs
    }

    private fun overlap(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toLong() * (bottom - top)
        val smaller = min(a.width().toLong() * a.height(), b.width().toLong() * b.height()).coerceAtLeast(1)
        return intersection.toFloat() / smaller
    }

    /** Two chapters are the same chapter when they carry the same number. */
    private fun matchChapters(raw: List<Chapter>, translated: List<Chapter>): List<Pair<Chapter, Chapter>> {
        val byNumber = translated.associateBy { it.chapterNumber.key() }
        return raw.mapNotNull { chapter ->
            byNumber[chapter.chapterNumber.key()]?.let { chapter to it }
        }.sortedBy { it.first.chapterNumber }
    }

    private fun Double.key(): Long = (this * 100).toLong()

    private fun List<Chapter>.readable(): List<Chapter> = filterNot { it.isNotAvailable }

    private suspend fun detectScript(bitmap: Bitmap): OcrScript {
        val configured = preferences.sourceLanguage.get()
        if (configured != TranslationLanguage.AUTO) {
            configured.ocrScript?.let { return it }
        }

        var hangul = 0
        var cjk = 0
        var latin = 0
        ocrEngine.recognize(bitmap, OcrScript.JAPANESE).forEach { block ->
            block.text.forEach { char ->
                when {
                    char in '\uAC00'..'\uD7AF' -> hangul++
                    char.code in 0x3040..0x9FFF -> cjk++
                    char in 'a'..'z' || char in 'A'..'Z' -> latin++
                }
            }
        }
        return when {
            hangul > cjk + latin -> OcrScript.KOREAN
            cjk >= latin -> OcrScript.JAPANESE
            else -> OcrScript.LATIN
        }
    }

    private suspend fun fetch(source: HttpSource, page: Page): Bitmap? {
        val url = page.imageUrl ?: runCatching { source.getImageUrl(page) }.getOrNull() ?: return null
        val response = runCatching {
            networkHelper.client.newCall(Request.Builder().url(url).headers(source.headers).build()).execute()
        }.getOrNull() ?: return null
        return response.use { result ->
            if (!result.isSuccessful) return null
            val bytes = result.body.bytes()
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth > 0 && bounds.outWidth / sampleSize > MAX_WIDTH) sampleSize *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }

    companion object {
        private const val NO_SOURCE = "This source can't be read page by page, so nothing can be learned from it."
        private const val NO_SHARED_CHAPTERS = "The two entries have no chapters with the same number."
        private const val UNKNOWN = "Learning failed"
        private const val MIN_LETTERS = 2
        private const val MIN_OVERLAP = 0.3f
        private const val MAX_WIDTH = 1400
        private const val MAX_PAGES_PER_CHAPTER = 80
    }
}
