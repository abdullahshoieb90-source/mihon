package eu.kanade.tachiyomi.ui.browse.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.util.Screen
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun novelsTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    return TabContent(titleRes = MR.strings.novels) { padding, _ ->
        val graph = LocalContext.current.appGraph
        val incognito by graph.basePreferences.incognitoMode.collectAsState()
        Column(Modifier.padding(padding)) {
            ListItem(
                modifier = Modifier.clickable { navigator.push(NovelCatalogueScreen()) },
                content = { Text(stringResource(MR.strings.novel_source_hindawi)) },
                supportingContent = { Text(stringResource(MR.strings.novel_source_summary)) },
            )
            if (!incognito) {
                val position = graph.readerPreferences.lastNovelPosition
                val lastRead by position.collectAsState()
                lastRead?.let { last ->
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable {
                            navigator.push(NovelBookScreen(last.bookId, last.bookTitle, last.chapterId))
                        },
                        content = { Text(last.bookTitle) },
                        supportingContent = { Text(stringResource(MR.strings.action_resume)) },
                        trailingContent = {
                            IconButton(onClick = position::delete) {
                                Icon(MaterialSymbols.Rounded.Close, stringResource(MR.strings.novel_forget_position))
                            }
                        },
                    )
                }
            }
        }
    }
}

private class NovelCatalogueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val source = rememberHindawiNovels()
        var page by rememberSaveable { mutableIntStateOf(1) }
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.novel_source_hindawi),
                    subtitle = stringResource(MR.strings.novels),
                    navigateUp = navigator::pop,
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NovelContent(
                    request = page,
                    emptyMessage = MR.strings.novel_empty_catalogue,
                    load = { source.catalogue(page).takeIf { it.books.isNotEmpty() } },
                ) { catalogue ->
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(catalogue.books, key = { it.id }) { book ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        navigator.push(NovelBookScreen(book.id, book.title))
                                    },
                                    content = { Text(book.title) },
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { page-- }, enabled = page > 1) {
                                Text(stringResource(MR.strings.spen_previous_page))
                            }
                            TextButton(onClick = { page++ }, enabled = catalogue.hasNextPage) {
                                Text(stringResource(MR.strings.spen_next_page))
                            }
                        }
                    }
                }
            }
        }
    }
}
