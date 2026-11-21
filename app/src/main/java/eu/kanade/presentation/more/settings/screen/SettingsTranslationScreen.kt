package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.displayName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.translation.LearnedPair
import eu.kanade.tachiyomi.data.translation.LearnedStyle
import eu.kanade.tachiyomi.data.translation.TranslationCacheStats
import eu.kanade.tachiyomi.data.translation.TranslationUsage
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.domain.translation.model.TranslationLanguage
import tachiyomi.domain.translation.model.TranslationLetterCase
import tachiyomi.domain.translation.model.TranslationPlateStyle
import tachiyomi.domain.translation.model.TranslationProvider
import tachiyomi.domain.translation.model.TranslationTextColor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

object SettingsTranslationScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val preferences = remember { context.appGraph.translationPreferences }
        val basePreferences = remember { context.appGraph.basePreferences }
        val provider by preferences.provider.collectAsState()
        val memory = remember { context.appGraph.translationMemoryStore }
        var styles by remember { mutableStateOf<List<LearnedStyle>>(emptyList()) }
        LaunchedEffect(Unit) { styles = memory.learnedStyles() }

        val autoLabel = stringResource(MR.strings.translation_language_auto)
        val languageNames = TranslationLanguage.entries.associateWith { it.displayName() }
        val sourceLanguageNames = mapOf(TranslationLanguage.AUTO to autoLabel) +
            languageNames.minus(TranslationLanguage.AUTO)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_languages),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.sourceLanguage,
                        entries = sourceLanguageNames,
                        title = stringResource(MR.strings.pref_translation_source_language),
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.translation_source_language_hint),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.targetLanguage,
                        entries = languageNames.minus(TranslationLanguage.AUTO),
                        title = stringResource(MR.strings.pref_translation_target_language),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_translation_appearance),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.textColor,
                        entries = mapOf(
                            TranslationTextColor.AUTO to stringResource(MR.strings.translation_color_auto),
                            TranslationTextColor.WHITE to stringResource(MR.strings.translation_color_white),
                            TranslationTextColor.BLACK to stringResource(MR.strings.translation_color_black),
                            TranslationTextColor.RED to stringResource(MR.strings.translation_color_red),
                            TranslationTextColor.YELLOW to stringResource(MR.strings.translation_color_yellow),
                        ),
                        title = stringResource(MR.strings.pref_translation_text_color),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.textScale,
                        valueRange = 80..180,
                        valueText = { scale -> "$scale%" },
                        title = stringResource(MR.strings.pref_translation_text_scale),
                        subtitle = stringResource(MR.strings.pref_translation_text_scale_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.plateStyle,
                        entries = mapOf(
                            TranslationPlateStyle.BUBBLE to
                                stringResource(MR.strings.translation_plate_bubble),
                            TranslationPlateStyle.ROUNDED to
                                stringResource(MR.strings.translation_plate_rounded),
                            TranslationPlateStyle.BOX to
                                stringResource(MR.strings.translation_plate_box),
                        ),
                        title = stringResource(MR.strings.pref_translation_plate_style),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.boldText,
                        title = stringResource(MR.strings.pref_translation_bold),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.explicitContent,
                        title = stringResource(MR.strings.pref_translation_explicit),
                        subtitle = stringResource(MR.strings.pref_translation_explicit_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.letterCase,
                        entries = mapOf(
                            TranslationLetterCase.ORIGINAL to stringResource(MR.strings.translation_case_original),
                            TranslationLetterCase.UPPER to stringResource(MR.strings.translation_case_upper),
                            TranslationLetterCase.LOWER to stringResource(MR.strings.translation_case_lower),
                        ),
                        title = stringResource(MR.strings.pref_translation_letter_case),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_service),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.autoTranslate,
                        title = stringResource(MR.strings.pref_translation_auto),
                        subtitle = stringResource(MR.strings.pref_translation_auto_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.fallbackToGoogle,
                        title = stringResource(MR.strings.pref_translation_fallback),
                        subtitle = stringResource(MR.strings.pref_translation_fallback_summary),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_translation_style),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.styleSourceId,
                        entries = buildMap {
                            put(-1L, stringResource(MR.strings.translation_style_none))
                            styles.forEach { style ->
                                put(
                                    style.sourceId,
                                    "${style.sourceName.ifBlank { style.sourceId.toString() }} — ${style.pairs}",
                                )
                            }
                        },
                        title = stringResource(MR.strings.pref_translation_style),
                        subtitle = stringResource(
                            if (styles.isEmpty()) {
                                MR.strings.pref_translation_style_empty
                            } else {
                                MR.strings.pref_translation_style_summary
                            },
                        ),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_glossary),
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.translation_glossary_hint),
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.translation_glossary_summary),
                    ) {
                        GlossaryManager()
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_usage),
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.translation_usage_hint),
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.translation_usage),
                    ) {
                        UsageCounter()
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_cache),
                preferenceItems = listOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.translation_cache),
                    ) {
                        TranslationCacheManager()
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_translation_ocr),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.ocrQuality,
                        valueRange = 1..3,
                        valueText = { quality ->
                            stringResource(
                                when (quality) {
                                    1 -> MR.strings.translation_ocr_fast
                                    3 -> MR.strings.translation_ocr_accurate
                                    else -> MR.strings.translation_ocr_balanced
                                },
                            )
                        },
                        title = stringResource(MR.strings.pref_translation_ocr_quality),
                        subtitle = stringResource(MR.strings.pref_translation_ocr_quality_summary),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_service),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.provider,
                        entries = mapOf(
                            TranslationProvider.GOOGLE to stringResource(MR.strings.translation_provider_google),
                            TranslationProvider.DEEPL to stringResource(MR.strings.translation_provider_deepl),
                            TranslationProvider.LIBRE_TRANSLATE to
                                stringResource(MR.strings.translation_provider_libre),
                            TranslationProvider.OPENAI to stringResource(MR.strings.translation_provider_openai),
                            TranslationProvider.GEMINI to stringResource(MR.strings.translation_provider_gemini),
                            TranslationProvider.CLAUDE to stringResource(MR.strings.translation_provider_claude),
                            TranslationProvider.ON_DEVICE to
                                stringResource(MR.strings.translation_provider_on_device),
                        ),
                        title = stringResource(MR.strings.pref_translation_provider),
                        subtitle = if (provider == TranslationProvider.ON_DEVICE) {
                            stringResource(MR.strings.translation_on_device_hint)
                        } else {
                            null
                        },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.deeplApiKey,
                        title = stringResource(MR.strings.pref_translation_deepl_api_key),
                        subtitle = stringResource(MR.strings.pref_translation_deepl_api_key_summary),
                        visible = provider == TranslationProvider.DEEPL,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.libreTranslateUrl,
                        title = stringResource(MR.strings.pref_translation_libre_url),
                        visible = provider == TranslationProvider.LIBRE_TRANSLATE,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.libreTranslateApiKey,
                        title = stringResource(MR.strings.pref_translation_libre_api_key),
                        subtitle = stringResource(MR.strings.pref_translation_libre_api_key_summary),
                        visible = provider == TranslationProvider.LIBRE_TRANSLATE,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.openAiApiKey,
                        title = stringResource(MR.strings.pref_translation_ai_key),
                        subtitle = stringResource(MR.strings.pref_translation_ai_key_summary),
                        visible = provider == TranslationProvider.OPENAI,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.openAiModel,
                        title = stringResource(MR.strings.pref_translation_ai_model),
                        subtitle = stringResource(MR.strings.pref_translation_ai_model_summary),
                        visible = provider == TranslationProvider.OPENAI,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.geminiApiKey,
                        title = stringResource(MR.strings.pref_translation_ai_key),
                        subtitle = stringResource(MR.strings.pref_translation_ai_key_summary),
                        visible = provider == TranslationProvider.GEMINI,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.geminiModel,
                        title = stringResource(MR.strings.pref_translation_ai_model),
                        subtitle = stringResource(MR.strings.pref_translation_ai_model_summary),
                        visible = provider == TranslationProvider.GEMINI,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.claudeApiKey,
                        title = stringResource(MR.strings.pref_translation_ai_key),
                        subtitle = stringResource(MR.strings.pref_translation_ai_key_summary),
                        visible = provider == TranslationProvider.CLAUDE,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.claudeModel,
                        title = stringResource(MR.strings.pref_translation_ai_model),
                        subtitle = stringResource(MR.strings.pref_translation_ai_model_summary),
                        visible = provider == TranslationProvider.CLAUDE,
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_app_updates),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = basePreferences.appUpdates,
                        title = stringResource(MR.strings.pref_app_updates_automatic),
                        subtitle = stringResource(MR.strings.pref_app_updates_automatic_summary),
                    ),
                ),
                // Builds without the updater have nothing for the switch to turn on.
                visible = BuildConfig.UPDATER_ENABLED,
            ),
        )
    }

    @Composable
    private fun GlossaryManager() {
        val context = LocalContext.current
        val glossary = remember { context.appGraph.glossaryStore }
        val scope = rememberCoroutineScope()
        var terms by remember { mutableStateOf<List<LearnedPair>>(emptyList()) }
        var showDialog by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { terms = glossary.list() }

        TextButton(onClick = { showDialog = true }) {
            Text(text = stringResource(MR.strings.translation_glossary_count, terms.size))
        }

        if (showDialog) {
            var source by remember { mutableStateOf("") }
            var target by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(text = stringResource(MR.strings.translation_glossary)) },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        TextField(
                            value = source,
                            onValueChange = { source = it },
                            label = { Text(text = stringResource(MR.strings.translation_glossary_source)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = target,
                            onValueChange = { target = it },
                            label = { Text(text = stringResource(MR.strings.translation_glossary_target)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (terms.isEmpty()) {
                            Text(text = stringResource(MR.strings.translation_glossary_empty))
                        } else {
                            terms.forEach { term ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "${term.source} → ${term.target}",
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                glossary.remove(term.source)
                                                terms = glossary.list()
                                            }
                                        },
                                    ) {
                                        Text(text = stringResource(MR.strings.translation_glossary_delete))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                glossary.add(source, target)
                                terms = glossary.list()
                                source = ""
                                target = ""
                            }
                        },
                        enabled = source.isNotBlank() && target.isNotBlank(),
                    ) {
                        Text(text = stringResource(MR.strings.translation_glossary_add))
                    }
                },
                dismissButton = {
                    Row {
                        if (terms.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        glossary.clear()
                                        terms = glossary.list()
                                    }
                                },
                            ) {
                                Text(text = stringResource(MR.strings.translation_glossary_clear))
                            }
                        }
                        TextButton(onClick = { showDialog = false }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    }
                },
            )
        }
    }

    @Composable
    private fun TranslationCacheManager() {
        val context = LocalContext.current
        val cache = remember { context.appGraph.translationCacheStore }
        val scope = rememberCoroutineScope()
        var stats by remember { mutableStateOf<TranslationCacheStats?>(null) }
        var showConfirm by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { stats = cache.stats() }

        val current = stats ?: return

        TextButton(onClick = { showConfirm = true }, enabled = current.chapters > 0) {
            Text(
                text = stringResource(
                    MR.strings.translation_cache_summary,
                    current.chapters,
                    Formatter.formatFileSize(context, current.bytes),
                ),
            )
        }

        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text(text = stringResource(MR.strings.translation_cache_clear)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirm = false
                            scope.launch {
                                cache.clearAll()
                                stats = cache.stats()
                                context.toast(MR.strings.translation_cache_cleared)
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.translation_cache_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun UsageCounter() {
        val context = LocalContext.current
        val usageStore = remember { context.appGraph.translationUsageStore }
        val scope = rememberCoroutineScope()
        var usage by remember { mutableStateOf<TranslationUsage?>(null) }
        var showConfirm by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { usage = usageStore.snapshot() }

        val current = usage ?: return

        val providerNames = mapOf(
            TranslationProvider.GOOGLE to stringResource(MR.strings.translation_provider_google),
            TranslationProvider.DEEPL to stringResource(MR.strings.translation_provider_deepl),
            TranslationProvider.LIBRE_TRANSLATE to stringResource(MR.strings.translation_provider_libre),
            TranslationProvider.OPENAI to stringResource(MR.strings.translation_provider_openai),
            TranslationProvider.GEMINI to stringResource(MR.strings.translation_provider_gemini),
            TranslationProvider.CLAUDE to stringResource(MR.strings.translation_provider_claude),
            TranslationProvider.ON_DEVICE to stringResource(MR.strings.translation_provider_on_device),
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            if (current.month.requests == 0L) {
                UsageLine(text = stringResource(MR.strings.translation_usage_empty))
            } else {
                UsageLine(
                    text = stringResource(
                        MR.strings.translation_usage_today,
                        current.today.requests.counted(),
                        current.today.characters.counted(),
                    ),
                )
                UsageLine(
                    text = stringResource(
                        MR.strings.translation_usage_month,
                        current.month.requests.counted(),
                        current.month.characters.counted(),
                    ),
                )
                current.month.byProvider.forEach { (provider, counts) ->
                    val name = providerNames[provider] ?: provider.name
                    val quota = usageStore.monthlyFreeQuota(provider)
                    val detail = if (quota == null) {
                        stringResource(
                            MR.strings.translation_usage_provider,
                            counts.requests.counted(),
                            counts.characters.counted(),
                        )
                    } else {
                        stringResource(
                            MR.strings.translation_usage_quota,
                            counts.characters.counted(),
                            quota.counted(),
                        )
                    }
                    UsageLine(text = "$name · $detail")
                }
            }
            TextButton(onClick = { showConfirm = true }, enabled = current.month.requests > 0) {
                Text(text = stringResource(MR.strings.translation_usage_reset))
            }
        }

        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text(text = stringResource(MR.strings.translation_usage_reset)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirm = false
                            scope.launch {
                                usageStore.reset()
                                usage = usageStore.snapshot()
                                context.toast(MR.strings.translation_usage_reset_done)
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.translation_usage_reset))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun UsageLine(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** Thousands separated, so half a million characters reads as a number and not a wall of digits. */
    private fun Long.counted(): String = NumberFormat.getIntegerInstance().format(this)
}
