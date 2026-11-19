package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.TranslationLanguage
import java.io.ByteArrayOutputStream
import java.io.File

@Serializable
private data class CachedBlock(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val original: String,
    val translated: String,
    val lines: Int,
)

@Serializable
private data class CachedPage(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val source: String,
    val target: String,
    val provider: String,
    /** When the glossary last changed: a different value means the saved wording is out of date. */
    val glossaryVersion: Long,
    val blocks: List<CachedBlock>,
    /** The page thumbnail as a JPEG, so the text plate keeps following the page. Null when it couldn't be kept. */
    val preview: String? = null,
)

@Serializable
private data class CachedChapter(
    val pages: List<CachedPage> = emptyList(),
)

/** How much saved translation the device holds, for the settings screen. */
data class TranslationCacheStats(
    val chapters: Int,
    val bytes: Long,
)

/**
 * Translated pages kept on disk, so re-reading a chapter is instant and works without a connection.
 *
 * The page images live in the reader's own cache and are deleted by the system, but the translation is the
 * expensive part: OCR and a network call per page. One small JSON file per chapter holds the text blocks and the
 * thumbnail the text layer needs to pick a plate colour.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationCacheStore(
    private val context: Context,
    private val glossary: GlossaryStore,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** The saved translation of one page, or null when it was never saved or the languages changed since. */
    suspend fun load(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        source: String,
        target: String,
        provider: String,
    ): TranslatedPage? = withContext(Dispatchers.IO) {
        val glossaryVersion = glossary.version()
        mutex.withLock {
            val cached = read(fileFor(mangaId, chapterId))
                ?.pages
                ?.firstOrNull {
                    it.pageIndex == pageIndex &&
                        it.source == source &&
                        it.target == target &&
                        it.provider == provider &&
                        it.glossaryVersion == glossaryVersion
                }
                ?: return@withLock null
            val preview = cached.preview?.let { decodePreview(it) }
            TranslatedPage(
                pageIndex = cached.pageIndex,
                width = cached.width,
                height = cached.height,
                preview = preview,
                previewScale = preview?.let { it.width.toFloat() / cached.width } ?: 1f,
                blocks = cached.blocks.map {
                    TranslatedTextBlock(
                        box = Rect(it.left, it.top, it.right, it.bottom),
                        original = it.original,
                        translated = it.translated,
                        lines = it.lines,
                    )
                },
                source = TranslationLanguage.fromCode(cached.source),
                target = TranslationLanguage.fromCode(cached.target),
            )
        }
    }

    suspend fun save(mangaId: Long, chapterId: Long, page: TranslatedPage, provider: String) {
        withContext(Dispatchers.IO) {
            val glossaryVersion = glossary.version()
            mutex.withLock {
                val file = fileFor(mangaId, chapterId)
                val previous = read(file)?.pages.orEmpty()
                val entry = CachedPage(
                    pageIndex = page.pageIndex,
                    width = page.width,
                    height = page.height,
                    source = page.source.code,
                    target = page.target.code,
                    provider = provider,
                    glossaryVersion = glossaryVersion,
                    blocks = page.blocks.map {
                        CachedBlock(
                            left = it.box.left,
                            top = it.box.top,
                            right = it.box.right,
                            bottom = it.box.bottom,
                            original = it.original,
                            translated = it.translated,
                            lines = it.lines,
                        )
                    },
                    preview = page.preview?.let { encodePreview(it) },
                )
                val merged = (previous.filterNot { it.pageIndex == page.pageIndex } + entry)
                    .sortedBy { it.pageIndex }
                write(file, CachedChapter(merged))
                evictOldest()
            }
        }
    }

    /**
     * Drops one page from the saved chapter, so the next read translates it again.
     *
     * Used after a corrected term: the saved wording of that page is the one the reader just rejected, while the rest
     * of the chapter is still good.
     */
    suspend fun invalidatePage(mangaId: Long, chapterId: Long, pageIndex: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(mangaId, chapterId)
            val chapter = read(file) ?: return@withLock
            val kept = chapter.pages.filterNot { it.pageIndex == pageIndex }
            if (kept.size == chapter.pages.size) return@withLock
            if (kept.isEmpty()) {
                file.delete()
            } else {
                write(file, CachedChapter(kept))
            }
        }
    }

    suspend fun stats(): TranslationCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = directory().listFiles().orEmpty()
            TranslationCacheStats(chapters = files.size, bytes = files.sumOf { it.length() })
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory().listFiles().orEmpty().forEach { it.delete() }
        }
    }

    private fun read(file: File): CachedChapter? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<CachedChapter>(file.readText()) }.getOrNull()
    }

    private fun write(file: File, chapter: CachedChapter) {
        runCatching {
            directory().apply { mkdirs() }
            file.writeText(json.encodeToString(chapter))
        }
    }

    /** Keeps the cache from growing without bound: the least recently read chapters go first. */
    private fun evictOldest() {
        val files = directory().listFiles().orEmpty()
        if (files.size <= MAX_CHAPTERS) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CHAPTERS).forEach { it.delete() }
    }

    /**
     * The page thumbnail, kept as a JPEG.
     *
     * Wider than the text needs to be legible: the bubble's outline is read from it when the page is drawn, so a
     * coarse thumbnail would put an oval's edge between two pixels.
     */
    private fun encodePreview(bitmap: Bitmap): String? = runCatching {
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, stream)) return null
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun decodePreview(encoded: String): Bitmap? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun directory() = File(context.filesDir, DIRECTORY)

    private fun fileFor(mangaId: Long, chapterId: Long) = File(directory(), "${mangaId}_$chapterId.json")

    companion object {
        private const val DIRECTORY = "translation-cache"
        private const val MAX_CHAPTERS = 300
        private const val PREVIEW_QUALITY = 82
    }
}
