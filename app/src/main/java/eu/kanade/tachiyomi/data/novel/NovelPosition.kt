package eu.kanade.tachiyomi.data.novel

import kotlinx.serialization.Serializable

@Serializable
internal data class NovelPosition(
    val bookId: String,
    val bookTitle: String,
    val chapterId: String,
    val paragraph: Int,
    val offset: Int,
)
