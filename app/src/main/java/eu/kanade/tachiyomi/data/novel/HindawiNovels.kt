package eu.kanade.tachiyomi.data.novel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

internal class HindawiNovels(private val client: OkHttpClient) {

    suspend fun catalogue(page: Int): NovelCatalogue {
        val path = HindawiParser.CATALOGUE_PATH + if (page == 1) "" else "$page/"
        return read(path) { HindawiParser.catalogue(it, page) }
    }

    suspend fun chapters(bookId: String): List<NovelEntry> =
        read("/books/$bookId/") { HindawiParser.chapters(it, bookId) }

    suspend fun paragraphs(bookId: String, chapterId: String): List<String> =
        read("/books/$bookId/$chapterId/", HindawiParser::paragraphs)

    private suspend fun <T> read(path: String, parse: (Document) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(GET("https://www.safahat.org$path")).awaitSuccess().use { parse(it.asJsoup()) }
    }
}
