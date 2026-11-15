package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import android.content.Context
import android.graphics.BitmapFactory
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
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import java.io.ByteArrayOutputStream
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
 * Both versions show the same art, but the translated one is a picture and no on-device recognizer reads Arabic
 * lettering, so the pages are shown to a model that can see. What comes back is a line-by-line correspondence: the
 * raw wording and the human translation of it, which then teaches the translator the names and phrasing of a series.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationMemoryLearner(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val store: TranslationMemoryStore,
    private val preferences: TranslationPreferences,
    private val networkHelper: NetworkHelper,
) {

    private val reader = AiTranslationService(networkHelper, preferences)

    private val mutableState = MutableStateFlow<TranslationLearningState>(TranslationLearningState.Idle)
    val state: StateFlow<TranslationLearningState> = mutableState.asStateFlow()

    private fun unknown(): String = context.stringResource(MR.strings.translation_learn_failed, "")

    private fun hasVisionKey(): Boolean = listOf(
        preferences.geminiApiKey.get(),
        preferences.openAiApiKey.get(),
        preferences.claudeApiKey.get(),
    ).any { it.isNotBlank() }

    fun reset() {
        mutableState.value = TranslationLearningState.Idle
    }

    /**
     * Reads every chapter the two entries share and stores what it learned under [rawMangaId], the entry the raw
     * chapters are read from.
     */
    suspend fun learn(rawMangaId: Long, translatedMangaId: Long) = withContext(Dispatchers.IO) {
        mutableState.value = TranslationLearningState.Running(chapter = 0, chapters = 0, pairs = 0)
        try {
            if (hasVisionKey().not()) {
                return@withContext fail(context.stringResource(MR.strings.translation_learn_no_key))
            }

            val noManga = context.stringResource(MR.strings.translation_learn_no_manga)
            val noSource = context.stringResource(MR.strings.translation_learn_no_source)
            val rawManga = getManga.await(rawMangaId) ?: return@withContext fail(noManga)
            val translatedManga = getManga.await(translatedMangaId) ?: return@withContext fail(noManga)

            val rawSource = sourceManager.getOrStub(rawManga.source) as? HttpSource
                ?: return@withContext fail(noSource)
            val translatedSource = sourceManager.getOrStub(translatedManga.source) as? HttpSource
                ?: return@withContext fail(noSource)

            val chapters = matchChapters(
                raw = getChaptersByMangaId.await(rawMangaId).readable(),
                translated = getChaptersByMangaId.await(translatedMangaId).readable(),
            )
            if (chapters.isEmpty()) return@withContext fail(context.stringResource(MR.strings.translation_learn_no_shared))

            var learned = 0
            var done = 0
            chapters.forEach { (raw, translated) ->
                mutableState.value = TranslationLearningState.Running(done, chapters.size, learned)
                val pairs = readChapter(rawSource, translatedSource, raw, translated)
                if (pairs.isNotEmpty()) learned = store.remember(rawMangaId, pairs)
                done++
                mutableState.value = TranslationLearningState.Running(done, chapters.size, learned)
            }

            mutableState.value = TranslationLearningState.Done(
                chapters = done,
                pairs = learned,
                total = store.count(rawMangaId),
            )
        } catch (e: CancellationException) {
            mutableState.value = TranslationLearningState.Idle
            throw e
        } catch (e: TranslationException) {
            fail(e.message ?: unknown())
        } catch (e: Exception) {
            fail(e.message ?: unknown())
        }
    }

    private fun fail(message: String) {
        mutableState.value = TranslationLearningState.Failed(message)
    }

    private suspend fun readChapter(
        rawSource: HttpSource,
        translatedSource: HttpSource,
        raw: Chapter,
        translated: Chapter,
    ): List<LearnedPair> {
        val rawPages = runCatching { rawSource.getPageList(raw.toSChapter()) }.getOrNull().orEmpty()
        val translatedPages = runCatching { translatedSource.getPageList(translated.toSChapter()) }
            .getOrNull()
            .orEmpty()
        val count = min(rawPages.size, translatedPages.size).coerceAtMost(MAX_PAGES_PER_CHAPTER)
        if (count == 0) return emptyList()

        val pairs = mutableListOf<LearnedPair>()
        for (index in 0 until count) {
            val original = fetch(rawSource, rawPages[index]) ?: continue
            val translation = fetch(translatedSource, translatedPages[index]) ?: continue
            pairs += runCatching { reader.readTranslatedPage(original, translation) }
                .getOrElse { error ->
                    // A key that isn't accepted stops the whole run; a single unreadable page doesn't.
                    if (error is TranslationException) throw error else emptyList()
                }
        }
        return pairs
    }

    /** Two chapters are the same chapter when they carry the same number. */
    private fun matchChapters(raw: List<Chapter>, translated: List<Chapter>): List<Pair<Chapter, Chapter>> {
        val byNumber = translated.associateBy { it.chapterNumber.key() }
        return raw.mapNotNull { chapter ->
            byNumber[chapter.chapterNumber.key()]?.let { chapter to it }
        }.sortedBy { it.first.chapterNumber }
    }

    private fun Double.key(): Long = (this * 100).toLong()

    private fun List<Chapter>.readable(): List<Chapter> = filter { it.url.isNotBlank() }

    /** The page as JPEG bytes, small enough to send but sharp enough to read lettering. */
    private suspend fun fetch(source: HttpSource, page: Page): ByteArray? {
        val url = page.imageUrl ?: runCatching { source.getImageUrl(page) }.getOrNull() ?: return null
        val bytes = runCatching {
            networkHelper.client.newCall(Request.Builder().url(url).headers(source.headers).build()).execute()
                .use { response -> if (response.isSuccessful) response.body.bytes() else null }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth > 0 && bounds.outWidth / sampleSize > MAX_WIDTH) sampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
            },
        ) ?: return null

        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val MAX_WIDTH = 1100
        private const val JPEG_QUALITY = 80
        private const val MAX_PAGES_PER_CHAPTER = 80
    }
}
