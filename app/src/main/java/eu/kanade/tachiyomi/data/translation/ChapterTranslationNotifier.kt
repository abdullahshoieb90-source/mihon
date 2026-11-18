package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/**
 * Shows a chapter being translated ahead of reading, so leaving the manga screen doesn't look like it stopped.
 */
@Inject
class ChapterTranslationNotifier(
    private val context: Context,
) {

    fun progressBuilder(): NotificationCompat.Builder = context.notificationBuilder(
        Notifications.CHANNEL_TRANSLATION_LEARNING,
    ) {
        setSmallIcon(R.drawable.ic_mihon)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setContentTitle(context.stringResource(MR.strings.translation_prefetch))
        setContentText(context.stringResource(MR.strings.translation_prefetch_preparing))
        setProgress(0, 0, true)
    }

    /**
     * The run as the reader sees it while it happens: a bar that fills page by page, a tap that goes back to the series
     * it is working on, and a cancel that keeps the pages already finished.
     */
    fun showProgress(page: Int, pages: Int, mangaId: Long) {
        context.notify(
            Notifications.ID_CHAPTER_TRANSLATION,
            progressBuilder().apply {
                setContentIntent(NotificationReceiver.openEntryPendingActivity(context, mangaId))
                addAction(
                    MaterialSymbolsR.drawable.rounded_close,
                    context.stringResource(MR.strings.action_cancel),
                    NotificationReceiver.cancelChapterTranslationPendingBroadcast(context),
                )
                if (pages > 0) {
                    setContentText(context.stringResource(MR.strings.translation_prefetch_running, page, pages))
                    setProgress(pages, page, false)
                }
            }.build(),
        )
    }

    fun showDone(pages: Int) {
        context.cancelNotification(Notifications.ID_CHAPTER_TRANSLATION)
        context.notify(
            Notifications.ID_CHAPTER_TRANSLATION,
            context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_LEARNING) {
                setSmallIcon(R.drawable.ic_mihon)
                setAutoCancel(true)
                setContentTitle(context.stringResource(MR.strings.translation_prefetch))
                setContentText(context.stringResource(MR.strings.translation_prefetch_done, pages))
            }.build(),
        )
    }

    fun showFailure(message: String) {
        context.cancelNotification(Notifications.ID_CHAPTER_TRANSLATION)
        context.notify(
            Notifications.ID_CHAPTER_TRANSLATION,
            context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_LEARNING) {
                setSmallIcon(R.drawable.ic_mihon)
                setAutoCancel(true)
                setContentTitle(context.stringResource(MR.strings.translation_prefetch))
                setContentText(context.stringResource(MR.strings.translation_prefetch_failed, message))
            }.build(),
        )
    }
}
