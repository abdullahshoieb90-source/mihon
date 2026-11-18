package eu.kanade.tachiyomi.data.novel

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HindawiParserTest {

    @Test
    fun `catalogue reads cover titles and follows pagination only for novels`() {
        val page = Jsoup.parse(
            """
            <li class="bookCover"><a href="/books/1234/"><img alt="كتاب بعنوان الرحلة"></a></li>
            <a href="https://www.hindawi.org/books/1234/?ref=again"><img alt="Duplicate"></a>
            <a href="https://hindawi.org/books/4321/"><img alt="">A second book</a>
            <a href="https://example.org/books/1111/"><img alt="Another site"></a>
            <a href="/contributors/1234/"><img alt="Author"></a>
            <nav class="pagination">
                <a href="2/">٢</a>
                <a href="/books/categories/history/3/">Other category</a>
                <a href="https://example.org/books/categories/novels/3/">Other site</a>
            </nav>
            """.trimIndent(),
            "https://www.safahat.org/books/categories/novels/",
        )

        val catalogue = HindawiParser.catalogue(page, 1)
        assertEquals(listOf(NovelEntry("1234", "الرحلة"), NovelEntry("4321", "A second book")), catalogue.books)
        assertTrue(catalogue.hasNextPage)
        assertFalse(HindawiParser.catalogue(page, 2).hasNextPage)
    }

    @Test
    fun `contents keeps chapter order including front matter and excludes links to other books`() {
        val book = Jsoup.parse(
            """
            <a href="/books/1234/4/">Read now</a>
            <div class="bookIndex"><ul class="list">
                <li><a href="0.1/">مقدمة</a></li>
                <li><a href="10/">البداية</a></li>
                <li><a href="/books/1234/2/">العودة</a></li>
                <li><a href="10/#notes">Note in the same chapter</a></li>
                <li><a href="/books/9999/1/">Other book</a></li>
                <li><a href="https://example.org/books/1234/7/">Other site</a></li>
            </ul></div>
            """.trimIndent(),
            "https://www.hindawi.org/books/1234/",
        )

        assertEquals(
            listOf(NovelEntry("0.1", "مقدمة"), NovelEntry("10", "البداية"), NovelEntry("2", "العودة")),
            HindawiParser.chapters(book, "1234"),
        )
    }

    @Test
    fun `reader keeps inline text together and starts a new paragraph at block or line breaks`() {
        val chapter = Jsoup.parse(
            """
            <header>Book title</header><article class="chapterContent">
                <h1 class="title">Chapter title</h1>
                <div><p>قال <em>المسافر</em>:&nbsp;أهلًا.</p><p>وصلنا!<br>أخيرًا.</p></div>
                <div>Three <a href="#">&amp;</a> four.</div>
                <nav>Next</nav><script>ignored()</script><aside>Sidebar</aside>
                <div class="pages"><a href="../2/">الفصل التالي</a></div>
                <div class="shareActions">مشاركة</div>
            </article><footer>Another book</footer>
            """.trimIndent(),
        )

        assertEquals(
            listOf("قال المسافر: أهلًا.", "وصلنا!", "أخيرًا.", "Three & four."),
            HindawiParser.paragraphs(chapter),
        )
    }

    @Test
    fun `missing content does not turn a site error page into a book`() {
        val error = Jsoup.parse("<main><p>Service unavailable</p></main>")
        assertTrue(HindawiParser.catalogue(error, 1).books.isEmpty())
        assertTrue(HindawiParser.chapters(error, "1234").isEmpty())
        assertTrue(HindawiParser.paragraphs(error).isEmpty())
    }
}
