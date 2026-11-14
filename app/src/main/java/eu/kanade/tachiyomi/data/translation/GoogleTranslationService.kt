package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tachiyomi.domain.translation.model.TranslationLanguage

/**
 * Uses the endpoint behind Google Translate's web widget. It needs no key, but it's unofficial.
 *
 * The whole page goes out as a single request: every bubble is prefixed with a numbered marker, so the service sees
 * one text and can keep the names, the pronouns and the tone consistent between the bubbles. The markers survive the
 * translation untouched, and if they ever don't, the page falls back to translating bubble by bubble.
 */
class GoogleTranslationService(
    private val networkHelper: NetworkHelper,
) : TranslationService {

    override suspend fun translate(
        texts: List<String>,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()

        if (texts.count { it.isNotBlank() } > 1) {
            val batched = runCatching {
                translateBatch(texts, source, target)
            }.getOrNull()
            if (batched != null) return batched
        }

        return texts.mapIndexed { index, text ->
            if (text.isBlank()) {
                text
            } else {
                if (index > 0) delay(REQUEST_INTERVAL_MILLIS)
                translateWithRetry(text, source, target)
            }
        }
    }

    /**
     * One request for the whole page, answered with the same markers back so each translation finds its bubble.
     */
    private suspend fun translateBatch(
        texts: List<String>,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): List<String>? {
        val marked = texts.mapIndexed { index, text -> "$marker($index) $text" }
            .joinToString(separator = "\n")

        val answer = translateWithRetry(marked, source, target)
        val parts = splitByMarkers(answer)
        if (parts.size != texts.size) return null

        val restored = texts.indices.map { index ->
            parts[index]?.takeIf { texts[index].isNotBlank() } ?: texts[index]
        }
        // Every bubble that had text has to come back translated, otherwise the batch isn't trustworthy.
        if (texts.indices.any { texts[it].isNotBlank() && restored[it].isBlank() }) return null
        return restored
    }

    private fun splitByMarkers(answer: String): Map<Int, String> {
        val matches = MARKER_PATTERN.findAll(answer).toList()
        if (matches.isEmpty()) return emptyMap()

        return buildMap {
            matches.forEachIndexed { position, match ->
                val index = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
                val start = match.range.last + 1
                val end = matches.getOrNull(position + 1)?.range?.first ?: answer.length
                put(index, answer.substring(start, end).trim())
            }
        }
    }

    /**
     * The endpoint is unofficial and throttles bursts, so a single hiccup shouldn't drop a whole speech bubble.
     */
    private suspend fun translateWithRetry(
        text: String,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): String {
        var lastError: TranslationException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return translate(text, source, target)
            } catch (e: TranslationException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS * (attempt + 1))
            }
        }
        throw lastError ?: TranslationException(TranslationError.PROVIDER_ERROR)
    }

    private suspend fun translate(
        text: String,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): String {
        val request = if (text.length <= MAX_QUERY_LENGTH) {
            getRequest(text, source, target)
        } else {
            postRequest(text, source, target)
        }

        val body = networkHelper.client.executeForBody(request)
        val sentences = runCatching {
            Json.Default.parseToJsonElement(body).jsonArray[0].jsonArray
        }.getOrNull() ?: throw TranslationException(
            TranslationError.PROVIDER_ERROR,
            "Unexpected response from Google Translate",
        )

        return sentences.joinToString(separator = "") { sentence ->
            sentence.jsonArray.firstOrNull()?.jsonPrimitive?.content.orEmpty()
        }.ifBlank {
            throw TranslationException(TranslationError.PROVIDER_ERROR, "Empty translation from Google Translate")
        }
    }

    private fun getRequest(text: String, source: TranslationLanguage, target: TranslationLanguage): Request {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", source.googleCode())
            .addQueryParameter("tl", target.googleCode())
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        return Request.Builder()
            .url(url)
            .header("User-Agent", networkHelper.defaultUserAgentProvider())
            .build()
    }

    /** A page worth of dialogue doesn't fit in a URL. */
    private fun postRequest(text: String, source: TranslationLanguage, target: TranslationLanguage): Request {
        val form = FormBody.Builder()
            .add("client", "gtx")
            .add("sl", source.googleCode())
            .add("tl", target.googleCode())
            .add("dt", "t")
            .add("q", text)
            .build()
        return Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", networkHelper.defaultUserAgentProvider())
            .post(form)
            .build()
    }

    private fun TranslationLanguage.googleCode(): String = when (this) {
        TranslationLanguage.CHINESE_SIMPLIFIED -> "zh-CN"
        TranslationLanguage.CHINESE_TRADITIONAL -> "zh-TW"
        else -> code
    }

    companion object {
        private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        private const val REQUEST_INTERVAL_MILLIS = 100L
        private const val RETRY_DELAY_MILLIS = 400L
        private const val MAX_ATTEMPTS = 3
        private const val MAX_QUERY_LENGTH = 1_500

        /** `[[[3]]]`, tolerant of the spaces a translation engine may add. */
        private val MARKER_PATTERN = Regex("""\[\s*\[\s*\[\s*(\d+)\s*\]\s*\]\s*\]""")

        private fun marker(index: Int): String = "[[[$index]]]"
    }
}
