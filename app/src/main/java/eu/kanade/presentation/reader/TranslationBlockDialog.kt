package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.data.translation.TranslatedTextBlock
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Save
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * What a bubble says, in both languages.
 *
 * Long-tapping a translation over the page answers the question the drawing can't: what the original said. From here
 * the line can be copied, and the wording the translator chose can be kept for the rest of the series - which is what
 * turns a one-off correction into a translation that reads better every chapter.
 */
@Composable
fun TranslationBlockDialog(
    onDismissRequest: () -> Unit,
    block: TranslatedTextBlock,
    onRetranslatePage: (() -> Unit)?,
) {
    val context = LocalContext.current
    val glossary = remember { context.appGraph.glossaryStore }
    val scope = rememberCoroutineScope()
    var kept by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.translation_block_original),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Selected as a whole: picking out one name is the reason this screen exists.
            SelectionContainer {
                Text(
                    text = block.original,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                text = stringResource(MR.strings.translation_block_translation),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = block.translated,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val copyLabel = stringResource(
                if (copied) MR.strings.translation_block_copied else MR.strings.translation_block_copy,
            )

            Row(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = copyLabel,
                    icon = MaterialSymbols.Rounded.ContentCopy,
                    onClick = {
                        // Copied as the label says: the translation, which is what goes into a note or a chat.
                        context.copyToClipboard(copyLabel, block.translated)
                        copied = true
                    },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(
                        if (kept) MR.strings.translation_block_kept else MR.strings.translation_block_keep,
                    ),
                    icon = MaterialSymbols.Rounded.Save,
                    onClick = {
                        if (kept) return@ActionButton
                        scope.launch {
                            // The pair is filed as it stands: the original line and the wording it was given. The next
                            // page translated in this series sees it as a fixed term.
                            glossary.add(block.original, block.translated)
                            kept = true
                        }
                    },
                )
            }

            if (onRetranslatePage != null) {
                ActionButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    title = stringResource(MR.strings.translation_block_retranslate),
                    icon = MaterialSymbols.Rounded.Refresh,
                    onClick = {
                        onDismissRequest()
                        onRetranslatePage()
                    },
                )
            }

            Text(
                text = stringResource(MR.strings.translation_block_keep_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
