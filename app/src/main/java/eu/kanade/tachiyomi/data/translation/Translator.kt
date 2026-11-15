package eu.kanade.tachiyomi.data.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import tachiyomi.domain.translation.model.TranslationLanguage
import tachiyomi.domain.translation.model.TranslationProvider
import tachiyomi.domain.translation.service.TranslationPreferences

@Inject
@SingleIn(AppScope::class)
class Translator(
    private val preferences: TranslationPreferences,
    networkHelper: NetworkHelper,
) {

    private val google = GoogleTranslationService(networkHelper)
    private val deepL = DeepLTranslationService(networkHelper, preferences)
    private val libreTranslate = LibreTranslateService(networkHelper, preferences)
    private val ai = AiTranslationService(networkHelper, preferences)
    private val onDevice = OnDeviceTranslationService()

    val sourceLanguage: TranslationLanguage
        get() = preferences.sourceLanguage.get()

    val targetLanguage: TranslationLanguage
        get() = preferences.targetLanguage.get()

    suspend fun translate(text: String): String = translate(listOf(text)).first()

    suspend fun translate(texts: List<String>): List<String> = translate(texts, sourceLanguage, targetLanguage)

    suspend fun translate(
        texts: List<String>,
        source: TranslationLanguage,
        target: TranslationLanguage,
        hints: List<LearnedPair> = emptyList(),
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (target == TranslationLanguage.AUTO) {
            throw TranslationException(TranslationError.MISSING_TARGET_LANGUAGE)
        }

        val service = when (preferences.provider.get()) {
            TranslationProvider.GOOGLE -> google
            TranslationProvider.DEEPL -> deepL
            TranslationProvider.LIBRE_TRANSLATE -> libreTranslate
            TranslationProvider.OPENAI,
            TranslationProvider.GEMINI,
            TranslationProvider.CLAUDE,
            -> ai
            TranslationProvider.ON_DEVICE -> onDevice
        }
        // Only the chat models can be told about the reference wording; the others already got the names swapped
        // into the source text, which they leave alone.
        return when (preferences.provider.get()) {
            TranslationProvider.OPENAI,
            TranslationProvider.GEMINI,
            TranslationProvider.CLAUDE,
            -> ai.translate(texts, source, target, hints)
            else -> service.translate(texts, source, target)
        }
    }
}
