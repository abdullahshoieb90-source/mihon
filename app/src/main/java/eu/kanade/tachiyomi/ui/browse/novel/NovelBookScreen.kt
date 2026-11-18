package eu.kanade.tachiyomi.ui.browse.novel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.novel.NovelPosition
import kotlinx.coroutines.flow.filterNotNull
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

internal data class NovelBookScreen(
    private val bookId: String,
    private val bookTitle: String,
    private val initialChapter: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val source = rememberHindawiNovels()
        var chapterId by rememberSaveable { mutableStateOf(initialChapter) }
        val contentsState = rememberLazyListState()
        Scaffold(
            topBar = {
                AppBar(
                    title = bookTitle,
                    subtitle = stringResource(
                        if (chapterId == null) MR.strings.chapters else MR.strings.novel_source_hindawi,
                    ),
                    navigateUp = { if (chapterId != null) chapterId = null else navigator.pop() },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NovelContent(
                    request = bookId,
                    emptyMessage = MR.strings.no_chapters_error,
                    load = { source.chapters(bookId).ifEmpty { null } },
                ) { chapters ->
                    val index = chapters.indexOfFirst { it.id == chapterId }
                    if (index == -1) {
                        LazyColumn(state = contentsState) {
                            items(chapters, key = { it.id }) { chapter ->
                                ListItem(
                                    modifier = Modifier.clickable { chapterId = chapter.id },
                                    content = { Text(chapter.title) },
                                )
                            }
                        }
                    } else {
                        BackHandler { chapterId = null }
                        val chapter = chapters[index]
                        Column(Modifier.fillMaxSize()) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                NovelContent(
                                    request = chapter.id,
                                    emptyMessage = MR.strings.novel_chapter_empty,
                                    load = { source.paragraphs(bookId, chapter.id).ifEmpty { null } },
                                ) { paragraphs ->
                                    ChapterText(bookId, bookTitle, chapter.id, paragraphs)
                                }
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { chapterId = chapters[index - 1].id }, enabled = index > 0) {
                                    Text(stringResource(MR.strings.action_previous_chapter))
                                }
                                TextButton(
                                    onClick = { chapterId = chapters[index + 1].id },
                                    enabled = index < chapters.lastIndex,
                                ) {
                                    Text(stringResource(MR.strings.action_next_chapter))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterText(bookId: String, bookTitle: String, chapterId: String, paragraphs: List<String>) {
    val graph = LocalContext.current.appGraph
    val position = graph.readerPreferences.lastNovelPosition
    val incognito by graph.basePreferences.incognitoMode.collectAsState()
    val saved = remember(bookId, chapterId, incognito) {
        if (incognito) null else position.get()?.takeIf { it.bookId == bookId && it.chapterId == chapterId }
    }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (saved?.paragraph ?: 0).coerceIn(paragraphs.indices),
        initialFirstVisibleItemScrollOffset = (saved?.offset ?: 0).coerceAtLeast(0),
    )
    LaunchedEffect(state, incognito) {
        if (incognito) return@LaunchedEffect
        snapshotFlow {
            if (state.isScrollInProgress) null else state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }.filterNotNull().collect { (paragraph, offset) ->
            position.set(NovelPosition(bookId, bookTitle, chapterId, paragraph, offset))
        }
    }

    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(paragraphs) { paragraph ->
                Text(
                    text = paragraph,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 32.sp,
                        textDirection = TextDirection.ContentOrRtl,
                    ),
                )
            }
        }
    }
}
