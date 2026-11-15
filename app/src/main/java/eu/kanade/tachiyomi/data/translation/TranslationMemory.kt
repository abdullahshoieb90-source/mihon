package eu.kanade.tachiyomi.data.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One line of the reference translation: what the raw chapter said, and how the translated one said it. */
@Serializable
data class LearnedPair(
    val source: String,
    val target: String,
)

@Serializable
private data class MemoryFile(
    val pairs: List<LearnedPair> = emptyList(),
)

/**
 * Remembers how a series is already translated elsewhere, so the machine translation of its raw chapters can use the
 * same names, honorifics and turns of phrase instead of inventing its own for every chapter.
 *
 * The pairs are learned from a translated copy of the same series (see [TranslationMemoryLearner]) and are cheap to
 * keep: one small JSON file per manga, read once and held in memory.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationMemoryStore(
    private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = mutableMapOf<Long, List<LearnedPair>>()

    /** The pairs of this series whose lines share the most words with [texts], best first. */
    suspend fun recall(mangaId: Long, texts: List<String>, limit: Int = MAX_HINTS): List<LearnedPair> {
        if (texts.isEmpty()) return emptyList()
        val pairs = load(mangaId)
        if (pairs.isEmpty()) return emptyList()

        val words = texts.flatMap { it.words() }.toSet()
        if (words.isEmpty()) return emptyList()

        return pairs
            .map { pair -> pair to pair.source.score(words, texts) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (pair, _) -> pair }
    }

    suspend fun count(mangaId: Long): Int = load(mangaId).size

    /** Adds what was just learned, newest reading winning over an older one for the same source line. */
    suspend fun remember(mangaId: Long, pairs: List<LearnedPair>): Int = withContext(Dispatchers.IO) {
        if (pairs.isEmpty()) return@withContext count(mangaId)
        mutex.withLock {
            val existing = fileFor(mangaId).takeIf { it.exists() }?.let { read(it) }.orEmpty()
            val merged = LinkedHashMap<String, LearnedPair>()
            pairs.forEach { merged[it.source] = it }
            existing.forEach { merged.putIfAbsent(it.source, it) }
            val kept = merged.values.toList().takeLast(MAX_PAIRS)
            cache[mangaId] = kept
            write(mangaId, kept)
            kept.size
        }
    }

    suspend fun clear(mangaId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache.remove(mangaId)
            fileFor(mangaId).delete()
        }
    }

    private suspend fun load(mangaId: Long): List<LearnedPair> = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache[mangaId] ?: run {
                val file = fileFor(mangaId)
                val pairs = if (file.exists()) read(file) else emptyList()
                cache[mangaId] = pairs
                pairs
            }
        }
    }

    private fun read(file: File): List<LearnedPair> = runCatching {
        json.decodeFromString<MemoryFile>(file.readText()).pairs
    }.getOrDefault(emptyList())

    private fun write(mangaId: Long, pairs: List<LearnedPair>) {
        runCatching {
            val dir = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            File(dir, "$mangaId.json").writeText(json.encodeToString(MemoryFile(pairs)))
        }
    }

    private fun fileFor(mangaId: Long) = File(File(context.filesDir, DIRECTORY), "$mangaId.json")

    private fun String.words(): List<String> = split(' ', '\n', '\t', '.', ',', '!', '?', '…', '"', '\'', '(', ')')
        .map { it.trim().lowercase() }
        .filter { it.length >= 2 }

    private fun String.score(words: Set<String>, texts: List<String>): Int {
        val own = words().toSet()
        if (own.isEmpty()) return 0
        var score = own.count { it in words }
        // A line that is quoted exactly is worth far more than a shared common word.
        if (length >= 2 && texts.any { it.contains(this, ignoreCase = true) }) score += 10
        return score
    }

    companion object {
        private const val DIRECTORY = "translation-memory"
        private const val MAX_PAIRS = 20_000
        private const val MAX_HINTS = 16
    }
}

/**
 * Names and short terms are the part a machine translation gets wrong the most, and the part the reference
 * translation already settled. Swapping them into the source text before translating makes every provider keep them,
 * because a foreign name inside a sentence is copied through untouched.
 */
fun List<LearnedPair>.withNamesApplied(text: String): String {
    if (isEmpty()) return text
    var result = text
    forEach { pair ->
        val source = pair.source.trim()
        val target = pair.target.trim()
        val words = source.count { it == ' ' } + 1
        if (source.length < 2 || target.isEmpty() || words > MAX_NAME_WORDS) return@forEach
        if (!result.contains(source)) return@forEach
        result = result.replace(source, target)
    }
    return result
}

private const val MAX_NAME_WORDS = 3
