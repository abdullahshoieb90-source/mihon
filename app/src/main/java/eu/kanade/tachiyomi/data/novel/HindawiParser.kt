package eu.kanade.tachiyomi.data.novel

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

internal data class NovelEntry(val id: String, val title: String)

internal data class NovelCatalogue(val books: List<NovelEntry>, val hasNextPage: Boolean)

internal object HindawiParser {
    const val CATALOGUE_PATH = "/books/categories/novels/"

    private val bookPath = Regex("""/books/([0-9]+)/?""")
    private val chapterPath = Regex("""/books/([0-9]+)/([0-9]+(?:\.[0-9]+)*)/?""")
    private val siteLink = Regex("""https?://(?:www\.)?(?:safahat|hindawi)\.org(/[^?#]*)(?:[?#].*)?""")
    private val whitespace = Regex("[\\s\\u00a0]+")

    fun catalogue(document: Document, page: Int): NovelCatalogue {
        val books = document.select("a[href] img[alt]").mapNotNull { cover ->
            val link = cover.closest("a[href]") ?: return@mapNotNull null
            val id = bookPath.matchEntire(link.sitePath())?.groupValues?.get(1) ?: return@mapNotNull null
            val title = cover.attr("alt").removePrefix("كتاب بعنوان").trim()
                .ifEmpty { link.text() }
            if (title.isBlank()) null else NovelEntry(id, title)
        }.distinctBy { it.id }
        val hasNextPage = document.select(".pagination a[href], a[rel=next]").any {
            it.sitePath().trimEnd('/') == "$CATALOGUE_PATH${page + 1}"
        }
        return NovelCatalogue(books, hasNextPage)
    }

    fun chapters(document: Document, bookId: String): List<NovelEntry> =
        document.select(".bookIndex a[href]").mapNotNull { link ->
            val match = chapterPath.matchEntire(link.sitePath()) ?: return@mapNotNull null
            val (book, chapter) = match.destructured
            val title = link.text()
            if (book != bookId || title.isBlank()) null else NovelEntry(chapter, title)
        }.distinctBy { it.id }

    fun paragraphs(document: Document): List<String> {
        val article = document.selectFirst(".chapterContent") ?: return emptyList()
        article.select(
            "h1.title, nav, aside, script, style, noscript, form, [hidden], .download-icons, .shareActions, .pages",
        ).remove()
        return buildList {
            val paragraph = StringBuilder()
            fun finishParagraph() {
                if (paragraph.isEmpty()) return
                val text = whitespace.replace(paragraph, " ").trim()
                if (text.isNotEmpty()) add(text)
                paragraph.clear()
            }

            // Block elements and line breaks divide prose; inline markup must not split words or duplicate text.
            article.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> paragraph.append(node.wholeText)
                        is Element -> if (node.isBlock || node.normalName() == "br") finishParagraph()
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.isBlock) finishParagraph()
                }
            })
        }
    }

    private fun Element.sitePath(): String = siteLink.matchEntire(absUrl("href"))?.groupValues?.get(1).orEmpty()
}
