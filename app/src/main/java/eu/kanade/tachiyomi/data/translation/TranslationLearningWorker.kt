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
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.system.logcat

/**
 * Runs the learning in the background so leaving the app, or reading something else, doesn't stop it.
 *
 * The run is kept alive by a foreground notification with its progress, and the pairs it collects are written after
 * every chapter, so a killed process loses at most the chapter it was in.
 */
class TranslationLearningWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject
    private lateinit var learner: TranslationMemoryLearner

    @Inject
    private lateinit var notifier: TranslationLearningNotifier

    override suspend fun doWork(): Result {
        graph.inject(this)

        val rawId = inputData.getLong(RAW_MANGA_ID, -1L)
        val translatedId = inputData.getLong(TRANSLATED_MANGA_ID, -1L)
        if (rawId <= 0 || translatedId <= 0) return Result.failure()

        setForegroundSafely()

        return coroutineScope {
            val watcher = launch {
                learner.state.collect { state ->
                    if (state is TranslationLearningState.Running) {
                        notifier.showProgress(state.chapter, state.chapters, state.pairs)
                    }
                }
            }
            try {
                learner.learn(rawId, translatedId)
                when (val state = learner.state.value) {
                    is TranslationLearningState.Done -> {
                        notifier.showDone(state.pairs, state.chapters)
                        Result.success()
                    }
                    is TranslationLearningState.Failed -> {
                        notifier.showFailure(state.message)
                        Result.failure()
                    }
                    else -> Result.success()
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                notifier.showFailure(e.message.orEmpty())
                Result.failure()
            } finally {
                watcher.cancel()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_TRANSLATION_LEARNING,
        notifier.progressBuilder().build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        },
    )

    companion object {
        private const val RAW_MANGA_ID = "raw_manga_id"
        private const val TRANSLATED_MANGA_ID = "translated_manga_id"
        private const val TAG = "TranslationLearning"

        fun start(context: Context, rawMangaId: Long, translatedMangaId: Long) {
            val request = OneTimeWorkRequestBuilder<TranslationLearningWorker>()
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        RAW_MANGA_ID to rawMangaId,
                        TRANSLATED_MANGA_ID to translatedMangaId,
                    ),
                )
                .build()
            // KEEP: a run already going is not restarted by asking again.
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
