package eu.kanade.tachiyomi.data.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.TranslationProvider
import java.io.File
import kotlin.time.Clock

/** What one provider was asked for over a stretch of time. */
data class ProviderUsage(
    val requests: Long,
    val characters: Long,
)

/** Usage over one stretch of time, split by provider and heaviest first. */
data class UsagePeriod(
    val requests: Long,
    val characters: Long,
    val byProvider: List<Pair<TranslationProvider, ProviderUsage>>,
)

data class TranslationUsage(
    val today: UsagePeriod,
    val month: UsagePeriod,
)

@Serializable
private data class UsageCounters(
    val requests: Long = 0,
    val characters: Long = 0,
)

@Serializable
private data class UsageFile(
    /** Day ("2026-11-17") to provider name to its counters for that day. */
    val days: Map<String, Map<String, UsageCounters>> = emptyMap(),
)

/**
 * How much each translation provider has been asked for.
 *
 * Every provider bills or caps by characters, so a page is counted as the characters it sent, and the counts are
 * kept per day: the month is what a quota covers, and today is what a reader who just burned through a key wants
 * to see. On-device translation is counted too, since it still costs time and battery.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationUsageStore(
    private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cache: UsageFile? = null

    suspend fun record(provider: TranslationProvider, characters: Int) {
        if (characters <= 0) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dayKey = today().key()
                val loaded = loadLocked()
                val forDay = loaded.days[dayKey].orEmpty()
                val previous = forDay[provider.name] ?: UsageCounters()
                val counted = UsageCounters(
                    requests = previous.requests + 1,
                    characters = previous.characters + characters.toLong(),
                )
                val updated = forDay + (provider.name to counted)
                val file = UsageFile((loaded.days + (dayKey to updated)).pruned())
                cache = file
                write(file)
            }
        }
    }

    suspend fun snapshot(): TranslationUsage = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = today()
            val dayKey = now.key()
            val monthPrefix = now.monthKey()
            val days = loadLocked().days
            val monthDays = days.filterKeys { it.startsWith(monthPrefix) }
            TranslationUsage(
                today = period(days[dayKey].orEmpty()),
                month = period(mergeDays(monthDays.values)),
            )
        }
    }

    suspend fun reset() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cache = UsageFile()
                write(UsageFile())
            }
        }
    }

    /**
     * The characters a free tier is known to allow each month, or null where the provider publishes no fixed cap.
     * Kept here rather than in the settings screen so a change of plan is a one-line change.
     */
    fun monthlyFreeQuota(provider: TranslationProvider): Long? = when (provider) {
        TranslationProvider.DEEPL -> DEEPL_FREE_CHARACTERS
        else -> null
    }

    private fun period(counters: Map<String, UsageCounters>): UsagePeriod {
        val byProvider = counters
            .mapNotNull { entry ->
                val provider = runCatching { TranslationProvider.valueOf(entry.key) }.getOrNull()
                    ?: return@mapNotNull null
                provider to ProviderUsage(entry.value.requests, entry.value.characters)
            }
            .sortedByDescending { (_, usage) -> usage.characters }
        return UsagePeriod(
            requests = byProvider.sumOf { (_, usage) -> usage.requests },
            characters = byProvider.sumOf { (_, usage) -> usage.characters },
            byProvider = byProvider,
        )
    }

    private fun mergeDays(days: Collection<Map<String, UsageCounters>>): Map<String, UsageCounters> {
        val merged = mutableMapOf<String, UsageCounters>()
        days.forEach { day ->
            day.forEach { (provider, counters) ->
                val previous = merged[provider] ?: UsageCounters()
                merged[provider] = UsageCounters(
                    requests = previous.requests + counters.requests,
                    characters = previous.characters + counters.characters,
                )
            }
        }
        return merged
    }

    /** Only the last two months are kept; older days are never shown again. */
    private fun Map<String, Map<String, UsageCounters>>.pruned(): Map<String, Map<String, UsageCounters>> {
        if (size <= MAX_DAYS) return this
        return toSortedMap().toList().takeLast(MAX_DAYS).toMap()
    }

    private fun loadLocked(): UsageFile {
        cache?.let { return it }
        val file = file()
        val loaded = if (file.exists()) {
            runCatching { json.decodeFromString<UsageFile>(file.readText()) }.getOrDefault(UsageFile())
        } else {
            UsageFile()
        }
        cache = loaded
        return loaded
    }

    private fun write(usage: UsageFile) {
        runCatching {
            directory().apply { mkdirs() }
            file().writeText(json.encodeToString(usage))
        }
    }

    private fun directory() = File(context.filesDir, DIRECTORY)

    private fun file() = File(directory(), FILE_NAME)

    companion object {
        private const val DIRECTORY = "translation-usage"
        private const val FILE_NAME = "usage.json"
        private const val MAX_DAYS = 62
        private const val DEEPL_FREE_CHARACTERS = 500_000L

        private fun today(): LocalDate =
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

        /** Built by hand rather than formatted: a formatter would write the digits in the device's numerals. */
        private fun LocalDate.key(): String = buildString {
            append(year.toString().padStart(4, '0'))
            append('-')
            append(monthNumber.toString().padStart(2, '0'))
            append('-')
            append(dayOfMonth.toString().padStart(2, '0'))
        }

        private fun LocalDate.monthKey(): String = key().substring(0, 7)
    }
}
