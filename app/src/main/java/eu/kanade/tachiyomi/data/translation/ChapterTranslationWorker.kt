package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import kotlin.coroutines.coroutineContext

/**
 * What a run came to, so the notification can say it in the reader's own words instead of a code.
 */
private sealed interface Outcome {
    data class Translated(val pages: Int) : Outcome
    data class Failed(val pages: Int) : Outcome
    data object NotDownloaded : Outcome
    data object Archived : Outcome
}

/**
 * Translates a whole chapter before it is opened, so reading it doesn't wait for the recognizer and the service on
 * every page.
 *
 * A downloaded chapter is read from the phone, which is what makes this worth running in the background: the pages are
 * already there, and only the translation has to be worked out. The result goes into the same cache the reader reads
 * from, so opening the chapter afterwards paints every page at once.
 *
 * The run is kept alive by a foreground notification, and one page failing doesn't stop the rest: a chapter translated
 * in pieces is still a chapter that opens faster.
 */
class ChapterTranslationWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject
    private lateinit var pageTranslator: PageTranslator

    @Inject
    private lateinit var notifier: ChapterTranslationNotifier

    @Inject
    private lateinit var sourceManager: SourceManager

    @Inject
    private lateinit var downloadProvider: DownloadProvider

    @Inject
    private lateinit var chapterRepository: ChapterRepository

    @Inject
    private lateinit var mangaRepository: MangaRepository

    override suspend fun doWork(): Result {
        graph.inject(this)

        val mangaId = inputData.getLong(MANGA_ID, -1L)
        val chapterId = inputData.getLong(CHAPTER_ID, -1L)
        if (mangaId <= 0 || chapterId <= 0) return Result.failure()

        setForegroundSafely()

        return try {
            when (val outcome = translateChapter(mangaId, chapterId)) {
                is Outcome.Translated -> {
                    notifier.showDone(outcome.pages)
                    Result.success()
                }
                Outcome.NotDownloaded -> {
                    notifier.showFailure(context.stringResource(MR.strings.translation_prefetch_not_downloaded))
                    Result.failure()
                }
                Outcome.Archived -> {
                    notifier.showFailure(context.stringResource(MR.strings.translation_prefetch_skipped_archive))
                    Result.failure()
                }
                is Outcome.Failed -> {
                    notifier.showFailure(
                        context.stringResource(MR.strings.translation_prefetch_failed_pages, outcome.pages),
                    )
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            notifier.showFailure(e.message.orEmpty())
            Result.failure()
        }
    }

    private suspend fun translateChapter(mangaId: Long, chapterId: Long): Outcome {
        val chapter = chapterRepository.getChapterById(chapterId) ?: return Outcome.NotDownloaded
        // A removed series throws rather than answering null, and that is a cancelled run, not a crash.
        val manga = runCatching { mangaRepository.getMangaById(mangaId) }.getOrNull()
            ?: return Outcome.NotDownloaded
        // A removed extension leaves the pages unreachable even when the folder is still on the phone.
        val source = sourceManager.get(manga.source) ?: return Outcome.NotDownloaded

        // Only what is on the phone: a chapter that still has to be fetched would make this a download job.
        val chapterDir = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        ) ?: return Outcome.NotDownloaded

        // Archived chapters hold their pages inside one file, which the reader unpacks; a background run leaves those
        // alone rather than unpacking a whole archive twice.
        if (chapterDir.isFile) return Outcome.Archived

        val files = withContext(Dispatchers.IO) {
            // The reader's own filter, so the page indexes here are the ones the reader will show.
            chapterDir.listFiles().orEmpty()
                .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedBy { it.name }
        }
        if (files.isEmpty()) return Outcome.NotDownloaded

        var done = 0
        for ((index, file) in files.withIndex()) {
            // WorkManager can stop a run when the phone needs its resources back. Stopping between pages keeps the
            // ones already translated.
            coroutineContext.ensureActive()
            notifier.showProgress(index + 1, files.size, mangaId)
            // One unreadable page, or one provider hiccup, doesn't throw away the pages around it.
            val page = try {
                pageTranslator.translate(
                    pageIndex = index,
                    openStream = { file.openInputStream() },
                    mangaId = mangaId,
                    seriesTitle = manga.title,
                    chapterId = chapterId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Page ${index + 1} could not be translated ahead" }
                null
            }
            if (page != null) done++
        }
        // Every page failing is a different problem from nothing to translate: usually the connection or the
        // provider, and saying so saves the reader from re-downloading a chapter that is already on the phone.
        return if (done > 0) Outcome.Translated(done) else Outcome.Failed(files.size)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_CHAPTER_TRANSLATION,
        notifier.progressBuilder().build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        },
    )

    companion object {
        private const val MANGA_ID = "manga_id"
        private const val CHAPTER_ID = "chapter_id"
        private const val TAG = "ChapterTranslation"

        fun start(context: Context, mangaId: Long, chapterId: Long) {
            val request = OneTimeWorkRequestBuilder<ChapterTranslationWorker>()
                .addTag(TAG)
                .setInputData(workDataOf(MANGA_ID to mangaId, CHAPTER_ID to chapterId))
                .build()
            // APPEND_OR_REPLACE: translating another chapter while one is going should queue it, not drop it.
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
