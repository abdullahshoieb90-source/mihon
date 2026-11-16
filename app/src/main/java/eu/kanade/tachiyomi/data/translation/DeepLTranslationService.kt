package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import tachiyomi.domain.translation.model.TranslationLanguage
import tachiyomi.domain.translation.service.TranslationPreferences

class DeepLTranslationService(
    private val networkHelper: NetworkHelper,
    private val preferences: TranslationPreferences,
) : TranslationService {

    override suspend fun translate(
        texts: List<String>,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ): List<String> {
        val apiKey = preferences.deeplApiKey.get().trim()
        if (apiKey.isEmpty()) {
            throw TranslationException(TranslationError.MISSING_API_KEY)
        }
        if (texts.isEmpty()) return emptyList()

        // A page can hold more bubbles than DeepL accepts in one request.
        if (texts.size > MAX_TEXTS_PER_REQUEST) {
            return texts.chunked(MAX_TEXTS_PER_REQUEST).flatMap { translate(it, source, target) }
        }

        val form = FormBody.Builder()
            .apply {
                texts.forEach { add("text", it) }
                if (source != TranslationLanguage.AUTO) add("source_lang", source.deepLSourceCode())
                add("target_lang", target.deepLTargetCode())
            }
            .build()

        val preferred = if (apiKey.endsWith(FREE_KEY_SUFFIX)) FREE_ENDPOINT else ENDPOINT
        val other = if (preferred == FREE_ENDPOINT) ENDPOINT else FREE_ENDPOINT
        val body = try {
            execute(preferred, apiKey, form)
        } catch (e: TranslationException) {
            // A key copied without its ":fx" tail still works, it just has to be sent to the other host.
            if (e.detail?.contains("Wrong endpoint", ignoreCase = true) == true) {
                execute(other, apiKey, form)
            } else {
                throw e
            }
        }
        val translations = runCatching {
            Json.Default.parseToJsonElement(body).jsonObject.getValue("translations").jsonArray
        }.getOrNull() ?: throw TranslationException(TranslationError.PROVIDER_ERROR, "Unexpected response from DeepL")

        return translations.map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
    }

    private suspend fun execute(url: String, apiKey: String, form: FormBody): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "DeepL-Auth-Key $apiKey")
            .post(form)
            .build()
        return networkHelper.client.executeForBody(request)
    }

    private fun TranslationLanguage.deepLSourceCode(): String = when (this) {
        TranslationLanguage.CHINESE_SIMPLIFIED, TranslationLanguage.CHINESE_TRADITIONAL -> "ZH"
        else -> code.substringBefore('-').uppercase()
    }

    private fun TranslationLanguage.deepLTargetCode(): String = when (this) {
        TranslationLanguage.ENGLISH -> "EN-US"
        TranslationLanguage.PORTUGUESE -> "PT-PT"
        TranslationLanguage.CHINESE_SIMPLIFIED -> "ZH-HANS"
        TranslationLanguage.CHINESE_TRADITIONAL -> "ZH-HANT"
        else -> code.substringBefore('-').uppercase()
    }

    companion object {
        private const val ENDPOINT = "https://api.deepl.com/v2/translate"
        private const val FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"
        private const val FREE_KEY_SUFFIX = ":fx"
        private const val MAX_TEXTS_PER_REQUEST = 50
    }
}
