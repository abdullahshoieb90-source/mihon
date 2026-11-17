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

@Serializable
private data class GlossaryFile(
    val terms: List<LearnedPair> = emptyList(),
)

/**
 * Terms the reader fixed by hand: a name or phrase and the wording every translation must keep.
 *
 * Unlike the learned memory, which is read from a translated copy, these are typed in Settings and apply to every
 * series. They are swapped into the source text before translating (see [withGlossaryApplied]), so every provider
 * keeps them.
 */
@Inject
@SingleIn(AppScope::class)
class GlossaryStore(
    private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cache: List<LearnedPair>? = null

    suspend fun list(): List<LearnedPair> = load()

    suspend fun add(source: String, target: String) {
        val from = source.trim()
        val to = target.trim()
        if (from.isEmpty() || to.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = (loadLocked().filterNot { it.source == from } + LearnedPair(from, to))
                    .sortedBy { it.source.lowercase() }
                cache = updated
                write(updated)
            }
        }
    }

    suspend fun remove(source: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = loadLocked().filterNot { it.source == source }
                cache = updated
                write(updated)
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cache = emptyList()
                write(emptyList())
            }
        }
    }

    /** The terms longest first, so a phrase wins over the word inside it. */
    suspend fun terms(): List<LearnedPair> = load().sortedByDescending { it.source.length }

    private suspend fun load(): List<LearnedPair> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked(): List<LearnedPair> {
        cache?.let { return it }
        val file = file()
        val loaded = if (file.exists()) {
            runCatching { json.decodeFromString<GlossaryFile>(file.readText()).terms }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        cache = loaded
        return loaded
    }

    private fun write(terms: List<LearnedPair>) {
        runCatching {
            val dir = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            File(dir, FILE_NAME).writeText(json.encodeToString(GlossaryFile(terms)))
        }
    }

    private fun file() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    companion object {
        private const val DIRECTORY = "translation-glossary"
        private const val FILE_NAME = "global.json"
    }
}

/**
 * Swaps the reader's fixed terms into the source text before translating, so every provider keeps them: a foreign
 * name inside a sentence is copied through untouched.
 */
fun List<LearnedPair>.withGlossaryApplied(texts: List<String>): List<String> {
    if (isEmpty()) return texts
    val terms = mapNotNull { pair ->
        val source = pair.source.trim()
        val target = pair.target.trim()
        if (source.isEmpty() || target.isEmpty()) null else source to target
    }.sortedByDescending { (source, _) -> source.length }
    if (terms.isEmpty()) return texts

    return texts.map { text ->
        var result = text
        terms.forEach { (source, target) ->
            if (result.contains(source)) result = result.replace(source, target)
        }
        result
    }
}
