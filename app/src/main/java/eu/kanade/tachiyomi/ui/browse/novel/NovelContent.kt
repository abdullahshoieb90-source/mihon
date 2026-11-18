package eu.kanade.tachiyomi.ui.browse.novel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.novel.HindawiNovels
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
internal fun rememberHindawiNovels(): HindawiNovels {
    val graph = LocalContext.current.appGraph
    return remember(graph) { HindawiNovels(graph.networkHelper.client) }
}

@Composable
internal fun <T : Any> NovelContent(
    request: Any,
    emptyMessage: StringResource,
    load: suspend () -> T?,
    content: @Composable (T) -> Unit,
) {
    var attempt by remember(request) { mutableIntStateOf(0) }
    key(request, attempt) {
        val result by produceState<Result<T?>?>(null) {
            value = try {
                Result.success(load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load novel content" }
                Result.failure(e)
            }
        }
        val loaded = result
        val data = loaded?.getOrNull()
        when {
            loaded == null -> LoadingScreen()
            data != null -> content(data)
            else -> EmptyScreen(
                stringRes = if (loaded.isFailure) MR.strings.novel_load_failed else emptyMessage,
                actions = listOf(
                    EmptyScreenAction(MR.strings.action_retry, MaterialSymbols.Rounded.Refresh) { attempt++ },
                ),
            )
        }
    }
}
