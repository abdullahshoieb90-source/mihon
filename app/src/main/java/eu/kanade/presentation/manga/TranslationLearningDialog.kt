package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.translation.TranslationLearningState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data class TranslationLearningUiState(
    val visible: Boolean = false,
    val candidates: List<Pair<Long, String>> = emptyList(),
    val learning: TranslationLearningState = TranslationLearningState.Idle,
)

/**
 * Asks which entry holds the translated copy of this series, then reports how the reading is going.
 */
@Composable
fun TranslationLearningDialog(
    state: TranslationLearningUiState,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return

    val learning = state.learning
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.translation_learn)) },
        text = {
            when (learning) {
                TranslationLearningState.Idle -> {
                    if (state.candidates.isEmpty()) {
                        Text(stringResource(MR.strings.translation_learn_empty))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(MR.strings.translation_learn_summary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(MR.strings.translation_learn_pick),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                items(state.candidates, key = { it.first }) { (id, title) ->
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPick(id) }
                                            .padding(vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                is TranslationLearningState.Running -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(
                                MR.strings.translation_learn_running,
                                learning.chapter,
                                learning.chapters,
                                learning.pairs,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is TranslationLearningState.Done -> {
                    Text(
                        stringResource(
                            MR.strings.translation_learn_done,
                            learning.pairs,
                            learning.chapters,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is TranslationLearningState.Failed -> {
                    Text(
                        stringResource(MR.strings.translation_learn_failed, learning.message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            val running = learning is TranslationLearningState.Running
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (running) MR.strings.translation_learn_cancel else MR.strings.action_ok,
                    ),
                )
            }
        },
    )
}
