package eu.kanade.tachiyomi.data.translation

import android.util.Base64
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.model.PageReadingLine
import tachiyomi.domain.translation.model.PageReadingParser
import tachiyomi.domain.translation.model.TranslationLanguage
import tachiyomi.domain.translation.model.TranslationProvider
import tachiyomi.domain.translation.service.TranslationPreferences
import java.util.Locale

/**
 * Translates through a chat model the user brings their own key for.
 *
 * The whole page goes out as one JSON array, so the model sees the bubbles next to each other and keeps the
 * pronouns, the names and the tone consistent, which is where the free endpoints fall apart on Japanese and
 * Korean dialogue.
 */
private const val MAX_HINT_LINES = 12

@Inject
class AiTranslationService(
    private val networkHelper: NetworkHelper,
    private val preferences: TranslationPreferences,
) : TranslationService {

    override suspend fun translate(
        texts: List<String>,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): List<String> = translate(texts, source, target, emptyList())

    /**
     * [hints] are lines a translated copy of the same series already rendered, so the model can reuse those names
     * and turns of phrase instead of inventing its own.
     */
    suspend fun translate(
        texts: List<String>,
        source: TranslationLanguage,
        target: TranslationLanguage,
        hints: List<LearnedPair>,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()

        val backend = backendFor(preferences.provider.get())
        val key = apiKey(backend)
        if (key.isBlank()) throw TranslationException(TranslationError.MISSING_API_KEY)
        val model = model(backend)
        val language = languageInstruction(source, target) + referenceInstruction(hints)

        val answer = try {
            requestBatch(backend, key, model, language, texts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        answer?.let { raw ->
            parseArray(raw, texts.size)?.let { return it }
        }

        // A model that answered in prose, or dropped an entry, still gets a chance bubble by bubble.
        return texts.map { text ->
            if (text.isBlank()) {
                text
            } else {
                requestSingle(backend, key, model, language, text).trim()
            }
        }
    }

    /** One request for the whole page, answered with a JSON array in the same order. */
    private suspend fun requestBatch(
        backend: AiBackend,
        key: String,
        model: String,
        language: String,
        texts: List<String>,
    ): String {
        val payload = buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } }.toString()
        val system = "You translate comic dialogue. $language " +
            "The user sends a JSON array of speech bubbles. Answer with only a JSON array of the same length, " +
            "holding the translation of each entry in the same order. No commentary, no code fences."
        return send(backend, key, model, system, payload)
    }

    private suspend fun requestSingle(
        backend: AiBackend,
        key: String,
        model: String,
        language: String,
        text: String,
    ): String {
        val system = "You translate comic dialogue. $language Answer with the translation alone, nothing else."
        return send(backend, key, model, system, text)
    }

    private suspend fun send(
        backend: AiBackend,
        key: String,
        model: String,
        system: String,
        user: String,
    ): String {
        val request = when (backend) {
            AiBackend.OPENAI -> openAiRequest(key, model, system, user)
            AiBackend.GEMINI -> geminiRequest(key, model, system, user)
            AiBackend.CLAUDE -> claudeRequest(key, model, system, user)
        }
        val body = networkHelper.client.executeForBody(request)
        return when (backend) {
            AiBackend.OPENAI -> body.jsonPath("choices", 0, "message", "content")
            AiBackend.GEMINI -> body.jsonPath("candidates", 0, "content", "parts", 0, "text")
            AiBackend.CLAUDE -> body.jsonPath("content", 0, "text")
        } ?: throw TranslationException(TranslationError.PROVIDER_ERROR, "Unexpected response from $backend")
    }

    private fun openAiRequest(key: String, model: String, system: String, user: String): Request {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }
        return Request.Builder()
            .url("$OPENAI_ENDPOINT/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun geminiRequest(key: String, model: String, system: String, user: String): Request {
        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") { addJsonObject { put("text", user) } }
                }
            }
            putJsonObject("generationConfig") { put("temperature", 0.2) }
        }
        return Request.Builder()
            .url("$GEMINI_ENDPOINT/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun claudeRequest(key: String, model: String, system: String, user: String): Request {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("temperature", 0.2)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }
        return Request.Builder()
            .url("$CLAUDE_ENDPOINT/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    /**
     * Models like to wrap their answer in a sentence or a code fence, so the array is cut out of the text.
     */
    private fun parseArray(raw: String, expected: Int): List<String>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null

        val array = runCatching {
            Json.Default.parseToJsonElement(raw.substring(start, end + 1)).jsonArray
        }.getOrNull() ?: return null
        if (array.size != expected) return null

        return array.map { element ->
            when (element) {
                is JsonPrimitive -> element.content
                else -> return null
            }
        }
    }

    /**
     * Walks the response object by key, with numbers stepping into arrays.
     */
    private fun String.jsonPath(vararg path: Any): String? {
        var current: JsonElement = runCatching { Json.Default.parseToJsonElement(this) }.getOrNull() ?: return null
        for (step in path) {
            val node = current
            current = when (node) {
                is JsonObject -> (step as? String)?.let { node[it] }
                is JsonArray -> (step as? Int)?.let { node.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return (current as? JsonPrimitive)?.content
    }

    private fun backendFor(provider: TranslationProvider): AiBackend = when (provider) {
        TranslationProvider.OPENAI -> AiBackend.OPENAI
        TranslationProvider.GEMINI -> AiBackend.GEMINI
        TranslationProvider.CLAUDE -> AiBackend.CLAUDE
        else -> throw TranslationException(TranslationError.PROVIDER_ERROR, "Not an AI provider")
    }

    private fun apiKey(backend: AiBackend): String = when (backend) {
        AiBackend.OPENAI -> preferences.openAiApiKey.get()
        AiBackend.GEMINI -> preferences.geminiApiKey.get()
        AiBackend.CLAUDE -> preferences.claudeApiKey.get()
    }.cleanKey()

    /** Pasting a key often brings quotes or a zero-width space along, and the provider then rejects it. */
    private fun String.cleanKey(): String = filter { it.code in 33..126 }.trim()

    private fun model(backend: AiBackend): String = when (backend) {
        AiBackend.OPENAI -> preferences.openAiModel.get().ifBlank { DEFAULT_OPENAI_MODEL }
        AiBackend.GEMINI -> preferences.geminiModel.get().ifBlank { DEFAULT_GEMINI_MODEL }
        AiBackend.CLAUDE -> preferences.claudeModel.get().ifBlank { DEFAULT_CLAUDE_MODEL }
    }.trim()

    /**
     * Reads the same page twice over — the original art and its human translation — and answers with the text of
     * each bubble in both languages.
     *
     * The translated copy is a picture, and no on-device recognizer reads Arabic lettering, so a model that can see
     * gets asked instead. Pairing the two versions by eye is also more reliable than matching bubble boxes.
     */
    suspend fun readTranslatedPage(
        rawImage: ByteArray,
        translatedImage: ByteArray,
    ): List<LearnedPair> {
        val withKeys = VISION_BACKENDS.filter { apiKey(it).isNotBlank() }
        val preferred = runCatching { backendFor(preferences.provider.get()) }.getOrNull()
        val backends = withKeys.sortedByDescending { it == preferred }
        if (backends.isEmpty()) throw TranslationException(TranslationError.MISSING_API_KEY)

        var failure: TranslationException? = null
        for (backend in backends) {
            val key = apiKey(backend)
            for (model in visionModels(backend)) {
                for (attempt in 0 until VISION_ATTEMPTS) {
                    if (attempt > 0) delay(VISION_RETRY_DELAY_MILLIS * attempt)
                    val result = runCatching { requestPageReading(backend, key, model, rawImage, translatedImage) }
                    result.getOrNull()?.let { return it }
                    val error = result.exceptionOrNull()
                    failure = error as? TranslationException
                        ?: TranslationException(TranslationError.PROVIDER_ERROR, error?.message)
                    // A rejected key won't fix itself by asking again.
                    if (failure?.error != TranslationError.PROVIDER_ERROR) throw failure!!
                    // An overloaded model is worth retrying; anything else moves on to the next model.
                    if (!isTemporary(failure?.detail)) break
                }
            }
        }
        throw failure ?: TranslationException(TranslationError.PROVIDER_ERROR, "No AI service answered")
    }

    /** Models get overloaded one at a time, so a second one is worth a try before giving up. */
    private fun visionModels(backend: AiBackend): List<String> = when (backend) {
        AiBackend.GEMINI -> listOf(model(backend), *GEMINI_FALLBACK_MODELS.toTypedArray()).distinct()
        AiBackend.OPENAI -> listOf(model(backend))
        AiBackend.CLAUDE -> listOf(model(backend))
    }

    private fun isTemporary(detail: String?): Boolean {
        val text = detail?.lowercase().orEmpty()
        return TEMPORARY_MARKERS.any { it in text }
    }

    /**
     * Reads a whole page — the art, the lettering and the situation — and answers with the translation of every
     * bubble and where it sits.
     *
     * This is the one that understands a page instead of a line: the model sees who is speaking to whom, reads
     * hand-lettered and decorated type that no on-device recognizer handles, and keeps a name the same across a page
     * because it saw the whole page. It costs one request per page, which is why the reader turns it on in settings.
     *
     * The first backend with a key answers; a model that is merely busy is retried and then swapped for a cheaper one,
     * and a rejected key moves on to the next service instead of failing the page.
     */
    suspend fun readPage(
        image: ByteArray,
        width: Int,
        height: Int,
        source: TranslationLanguage,
        target: TranslationLanguage,
        hints: List<LearnedPair> = emptyList(),
    ): List<PageReadingLine> {
        val withKeys = VISION_BACKENDS.filter { apiKey(it).isNotBlank() }
        if (withKeys.isEmpty()) throw TranslationException(TranslationError.MISSING_API_KEY)
        val preferred = runCatching { backendFor(preferences.provider.get()) }.getOrNull()
        val backends = withKeys.sortedByDescending { it == preferred }

        val instruction = pageReadingInstruction(source, target, hints)
        var failure: TranslationException? = null
        for (backend in backends) {
            val key = apiKey(backend)
            for (model in visionModels(backend)) {
                for (attempt in 0 until VISION_ATTEMPTS) {
                    if (attempt > 0) delay(VISION_RETRY_DELAY_MILLIS * attempt)
                    val result = runCatching {
                        val request = pageReadingRequest(backend, key, model, instruction, image)
                        val body = networkHelper.client.executeForBody(request)
                        val answer = when (backend) {
                            AiBackend.GEMINI -> body.jsonPath("candidates", 0, "content", "parts", 0, "text")
                            AiBackend.OPENAI -> body.jsonPath("choices", 0, "message", "content")
                            AiBackend.CLAUDE -> body.jsonPath("content", 0, "text")
                        } ?: throw TranslationException(TranslationError.PROVIDER_ERROR, "Unexpected answer")
                        PageReadingParser.parse(answer, width, height).map { line -> line.forPage(width, height) }
                    }
                    result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
                    val error = result.exceptionOrNull()
                    failure = error as? TranslationException
                        ?: TranslationException(TranslationError.PROVIDER_ERROR, error?.message)
                    if (failure?.error == TranslationError.MISSING_API_KEY) break
                    if (!isTemporary(failure?.detail)) break
                }
            }
        }
        throw failure ?: TranslationException(TranslationError.PROVIDER_ERROR, "No AI service read the page")
    }

    /** The same page, asked for in each service's own dialect. */
    private fun pageReadingRequest(
        backend: AiBackend,
        key: String,
        model: String,
        instruction: String,
        image: ByteArray,
    ): Request = when (backend) {
        AiBackend.GEMINI -> geminiVisionRequest(key, model, instruction, image)
        AiBackend.OPENAI -> openAiVisionRequest(key, model, instruction, image)
        AiBackend.CLAUDE -> claudeVisionRequest(key, model, instruction, image)
    }

    private fun pageReadingInstruction(
        source: TranslationLanguage,
        target: TranslationLanguage,
        hints: List<LearnedPair>,
    ): String {
        val from = if (source == TranslationLanguage.AUTO) {
            "the language it is written in"
        } else {
            source.localeName()
        }
        val examples = hints
            .filter { it.source.isNotBlank() && it.target.isNotBlank() }
            .take(MAX_HINT_LINES)
            .joinToString(separator = "\n") { "${it.source}  =>  ${it.target}" }
        val reference = if (examples.isBlank()) {
            ""
        } else {
            " Keep the names and the wording this series already uses, even when the lettering spells them " +
                "differently:\n$examples"
        }
        val maturity = if (preferences.explicitContent.get()) {
            " The comic is adult: keep its explicit wording literally, never soften, censor or summarise it."
        } else {
            ""
        }
        return "You are translating a comic page. Read the whole page: who is speaking to whom, the panel order, the " +
            "tone of the scene. Find every speech bubble, caption and thought bubble, and translate its lettering " +
            "from $from to ${target.localeName()}. Ignore sound effects drawn over the art and page numbers.$maturity" +
            reference + PAGE_READING_FORMAT
    }

    /**
     * One image, one instruction: the reading formats differ only in where the image goes, so the three builds below
     * share this shape.
     */
    private fun geminiVisionRequest(key: String, model: String, instruction: String, image: ByteArray): Request {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", instruction) }
                        addJsonObject { putBase64Image("inline_data", image) }
                    }
                }
            }
            putJsonObject("generationConfig") { put("temperature", 0.1) }
        }
        return Request.Builder()
            .url("$GEMINI_ENDPOINT/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun openAiVisionRequest(key: String, model: String, instruction: String, image: ByteArray): Request {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", instruction)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl(image)) }
                        }
                    }
                }
            }
        }
        return Request.Builder()
            .url("$OPENAI_ENDPOINT/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun claudeVisionRequest(key: String, model: String, instruction: String, image: ByteArray): Request {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("temperature", 0.1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", instruction)
                        }
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") { putClaudeImage(image) }
                        }
                    }
                }
            }
        }
        return Request.Builder()
            .url("$CLAUDE_ENDPOINT/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private suspend fun requestPageReading(
        backend: AiBackend,
        key: String,
        model: String,
        rawImage: ByteArray,
        translatedImage: ByteArray,
    ): List<LearnedPair> {
        val request = when (backend) {
            AiBackend.GEMINI -> geminiVisionRequest(key, model, PAGE_READING_INSTRUCTION, rawImage, translatedImage)
            AiBackend.OPENAI -> openAiVisionRequest(key, model, PAGE_READING_INSTRUCTION, rawImage, translatedImage)
            AiBackend.CLAUDE -> claudeVisionRequest(key, model, PAGE_READING_INSTRUCTION, rawImage, translatedImage)
        }
        val body = networkHelper.client.executeForBody(request)
        val answer = when (backend) {
            AiBackend.GEMINI -> body.jsonPath("candidates", 0, "content", "parts", 0, "text")
            AiBackend.OPENAI -> body.jsonPath("choices", 0, "message", "content")
            AiBackend.CLAUDE -> body.jsonPath("content", 0, "text")
        } ?: throw TranslationException(TranslationError.PROVIDER_ERROR, "Unexpected response from $backend")
        return parsePairs(answer)
    }

    private fun geminiVisionRequest(
        key: String,
        model: String,
        instruction: String,
        raw: ByteArray,
        translated: ByteArray,
    ): Request {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", instruction) }
                        addJsonObject { putBase64Image("inline_data", raw) }
                        addJsonObject { putBase64Image("inline_data", translated) }
                    }
                }
            }
            putJsonObject("generationConfig") { put("temperature", 0.1) }
        }
        return Request.Builder()
            .url("$GEMINI_ENDPOINT/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun openAiVisionRequest(
        key: String,
        model: String,
        instruction: String,
        raw: ByteArray,
        translated: ByteArray,
    ): Request {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", instruction)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl(raw)) }
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl(translated)) }
                        }
                    }
                }
            }
        }
        return Request.Builder()
            .url("$OPENAI_ENDPOINT/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun claudeVisionRequest(
        key: String,
        model: String,
        instruction: String,
        raw: ByteArray,
        translated: ByteArray,
    ): Request {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("temperature", 0.1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", instruction)
                        }
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") { putClaudeImage(raw) }
                        }
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") { putClaudeImage(translated) }
                        }
                    }
                }
            }
        }
        return Request.Builder()
            .url("$CLAUDE_ENDPOINT/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", JSON)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun JsonObjectBuilder.putBase64Image(field: String, bytes: ByteArray) {
        put("mime_type", "image/jpeg")
        put(field, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun JsonObjectBuilder.putClaudeImage(bytes: ByteArray) {
        put("type", "base64")
        put("media_type", "image/jpeg")
        put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun dataUrl(bytes: ByteArray): String =
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun parsePairs(answer: String): List<LearnedPair> {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching { Json.Default.parseToJsonElement(answer.substring(start, end + 1)) }
            .getOrNull() as? JsonArray ?: return emptyList()

        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val source = item["original"]?.jsonPrimitive?.content?.trim().orEmpty()
            val target = item["arabic"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (source.length < 2 || target.length < 2) null else LearnedPair(source, target)
        }
    }

    /** The line's fractions turned into page pixels, which is what the text layer draws in. */
    private fun PageReadingLine.forPage(width: Int, height: Int): PageReadingLine = copy(
        left = left * width,
        top = top * height,
        right = right * width,
        bottom = bottom * height,
    )

    private fun referenceInstruction(hints: List<LearnedPair>): String {
        if (hints.isEmpty()) return ""
        val examples = hints
            .filter { it.source.isNotBlank() && it.target.isNotBlank() }
            .take(MAX_HINT_LINES)
            .joinToString(separator = "\n") { "${it.source}  =>  ${it.target}" }
            .ifBlank { return "" }

        return " The series is already translated elsewhere; keep its wording for these established lines and reuse " +
            "the names and terms it chose, even when the raw text spells them differently:\n$examples"
    }

    private fun languageInstruction(source: TranslationLanguage, target: TranslationLanguage): String {
        val from = if (source == TranslationLanguage.AUTO) {
            "the language it is written in"
        } else {
            source.localeName()
        }
        val maturity = if (preferences.explicitContent.get()) {
            " The source is an adult comic: keep the explicit wording and tone exactly, translate sexual language " +
                "literally and never soften, censor, summarise or leave anything untranslated."
        } else {
            ""
        }
        return "Translate from $from to ${target.localeName()}.$maturity"
    }

    private fun TranslationLanguage.localeName(): String {
        val name = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
        return name.ifBlank { code }
    }

    private enum class AiBackend { OPENAI, GEMINI, CLAUDE }

    companion object {
        private val VISION_BACKENDS = listOf(AiBackend.GEMINI, AiBackend.OPENAI, AiBackend.CLAUDE)

        private const val PAGE_READING_FORMAT =
            " Answer with only a JSON array, one entry per bubble, in reading order. Each entry looks like " +
                "{\"original\": \"the lettering as it appears\", \"translated\": \"the translation\", " +
                "\"left\": 0.1, \"top\": 0.2, \"right\": 0.5, \"bottom\": 0.3} where the four numbers are " +
                "fractions of the page (0 is the left or top edge, 1 is the right or bottom edge). No commentary, " +
                "no code fences."

        private const val PAGE_READING_INSTRUCTION =
            "The first image is a comic page in its original language. The second image is the same page translated " +
                "by a human. Read both and answer with only a JSON array. Each entry is an object with \"original\" " +
                "(the text of one speech bubble in the first image) and \"arabic\" (the same bubble in the second " +
                "image). Skip sound effects and anything drawn over the art. Keep the original wording exactly."

        private const val VISION_ATTEMPTS = 2
        private const val VISION_RETRY_DELAY_MILLIS = 2500L

        private val GEMINI_FALLBACK_MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-latest")

        private val TEMPORARY_MARKERS = listOf(
            "high demand",
            "overloaded",
            "unavailable",
            "rate limit",
            "resource_exhausted",
            "try again",
            "timeout",
            "429",
            "500",
            "503",
        )

        private const val OPENAI_ENDPOINT = "https://api.openai.com/v1"
        private const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        private const val CLAUDE_ENDPOINT = "https://api.anthropic.com/v1"

        private const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        private const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"
        private const val DEFAULT_CLAUDE_MODEL = "claude-3-5-haiku-latest"

        private const val JSON = "application/json"
        private val JSON_MEDIA = JSON.toMediaType()
    }
}
