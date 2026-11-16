package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Shows a learning run while it happens in the background, so leaving the screen doesn't look like it stopped.
 */
@Inject
class TranslationLearningNotifier(
    private val context: Context,
) {

    fun progressBuilder(): NotificationCompat.Builder = context.notificationBuilder(
        Notifications.CHANNEL_TRANSLATION_LEARNING,
    ) {
        setSmallIcon(R.drawable.ic_mihon)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setContentTitle(context.stringResource(MR.strings.translation_learn))
        setContentText(context.stringResource(MR.strings.translation_learn_preparing))
        setProgress(0, 0, true)
    }

    fun showProgress(chapter: Int, chapters: Int, pairs: Int) {
        context.notify(
            Notifications.ID_TRANSLATION_LEARNING,
            progressBuilder().apply {
                if (chapters > 0) {
                    setContentText(
                        context.stringResource(MR.strings.translation_learn_running, chapter, chapters, pairs),
                    )
                    setProgress(chapters, chapter, false)
                }
            }.build(),
        )
    }

    fun showDone(pairs: Int, chapters: Int) {
        context.cancelNotification(Notifications.ID_TRANSLATION_LEARNING)
        context.notify(
            Notifications.ID_TRANSLATION_LEARNING,
            context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_LEARNING) {
                setSmallIcon(R.drawable.ic_mihon)
                setAutoCancel(true)
                setContentTitle(context.stringResource(MR.strings.translation_learn))
                setContentText(context.stringResource(MR.strings.translation_learn_done, pairs, chapters))
            }.build(),
        )
    }

    fun showFailure(message: String) {
        context.cancelNotification(Notifications.ID_TRANSLATION_LEARNING)
        context.notify(
            Notifications.ID_TRANSLATION_LEARNING,
            context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_LEARNING) {
                setSmallIcon(R.drawable.ic_mihon)
                setAutoCancel(true)
                setContentTitle(context.stringResource(MR.strings.translation_learn))
                setContentText(context.stringResource(MR.strings.translation_learn_failed, message))
            }.build(),
        )
    }

    fun clear() {
        context.cancelNotification(Notifications.ID_TRANSLATION_LEARNING)
    }
}
