package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GalleryPaginationTest {
    private val source = TestSource()

    @Test
    fun overlappingSearchesKeepTheirOwnCursorsWhenResponsesArriveOutOfOrder() {
        val first = source.searchMangaRequest(1, "first", FilterList())
        val second = source.searchMangaRequest(1, "second", FilterList())
        parse(second, "https://exhentai.org/?f_search=second&next=20&from=19")
        parse(first, "https://exhentai.org/?f_search=first&next=10")
        assertEquals("second", source.searchMangaRequest(2, "second", FilterList()).url.queryParameter("f_search"))
        assertEquals("20", source.searchMangaRequest(2, "second", FilterList()).url.queryParameter("next"))
        assertEquals("19", source.searchMangaRequest(2, "second", FilterList()).url.queryParameter("from"))
        assertEquals("10", source.searchMangaRequest(2, "first", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun startingAnotherSearchDoesNotClearAnExistingCursor() {
        parse(source.searchMangaRequest(1, "first", FilterList()), "https://exhentai.org/?next=10")
        source.searchMangaRequest(1, "second", FilterList())
        assertEquals("10", source.searchMangaRequest(2, "first", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun languagesCategoriesAndGalleryListsHaveIndependentCursors() {
        val variants = listOf(filters(0, 0), filters(1, 0), filters(3, 0), filters(1, 1), filters(1, 1, mangaOnly = true))
        val requests = variants.map { source.searchMangaRequest(1, "same query", it) }
        requests.withIndex().reversed().forEach { (index, request) ->
            parse(request, request.url.newBuilder().addQueryParameter("next", index.toString()).build().toString())
        }
        variants.forEachIndexed { index, filters ->
            val next = source.searchMangaRequest(2, "same query", filters)
            assertEquals(index.toString(), next.url.queryParameter("next"))
            assertEquals(requests[index].url.encodedPath, next.url.encodedPath)
            assertEquals(requests[index].headers, next.headers)
        }
    }

    @Test
    fun refreshedSearchIgnoresALateFirstPageResponse() {
        val old = source.searchMangaRequest(1, "query", FilterList())
        val refreshed = source.searchMangaRequest(1, "query", FilterList())
        parse(refreshed, "https://exhentai.org/?next=new")
        parse(old, "https://exhentai.org/?next=old")
        assertEquals("new", source.searchMangaRequest(2, "query", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun refreshedSearchIgnoresALateSecondPageResponse() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=old2")
        val oldSecond = source.searchMangaRequest(2, "query", FilterList())
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=new2")
        parse(source.searchMangaRequest(2, "query", FilterList()), "https://exhentai.org/?next=new3")
        parse(oldSecond, "https://exhentai.org/?next=old3")
        assertEquals("new3", source.searchMangaRequest(3, "query", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun retryingAPageUsesThatPagesCursor() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=second")
        val second = source.searchMangaRequest(2, "query", FilterList())
        parse(second, "https://exhentai.org/?next=third")
        assertEquals(second.url, source.searchMangaRequest(2, "query", FilterList()).url)
        assertEquals("third", source.searchMangaRequest(3, "query", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun latestAndSearchPaginationRemainSeparate() {
        val latest = source.latestUpdatesRequest(1)
        htmlResponse(latest, listing("https://exhentai.org/?next=latest")).use { source.latestUpdatesParse(it) }
        parse(source.searchMangaRequest(1, "", FilterList()), "https://exhentai.org/?next=search")
        assertEquals("latest", source.latestUpdatesRequest(2).url.queryParameter("next"))
        assertEquals("search", source.searchMangaRequest(2, "", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun refreshingWithoutANextPageDropsTheOldCursor() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=old")
        assertFalse(parse(source.searchMangaRequest(1, "query", FilterList()), null).hasNextPage)
        assertThrows(IOException::class.java) { source.searchMangaRequest(2, "query", FilterList()) }
    }

    @Test
    fun popularUsesTheWebsitePopularPageAndReturnsItsGalleries() {
        val request = source.popularMangaRequest(1)
        assertEquals("/popular", request.url.encodedPath)
        val result = htmlResponse(request, listing(null)).use { source.popularMangaParse(it) }
        assertFalse(result.hasNextPage)
        assertEquals("Gallery", result.mangas.single().title)
        assertEquals("/g/1/abcdef/?nw=always", result.mangas.single().url)
    }

    private fun parse(request: Request, next: String?) = htmlResponse(request, listing(next)).use {
        source.searchMangaParse(it).also { result ->
            assertEquals("Gallery", result.mangas.single().title)
            if (next != null) assertTrue(result.hasNextPage)
        }
    }

    private fun listing(next: String?) = """
        <table class="itg"><tr><td><a href="/g/1/abcdef/"><div class="glink">Gallery</div></a></td></tr></table>
        ${next?.let { "<a id='unext' href='${it.replace("&", "&amp;")}'>Next</a>" }.orEmpty()}
    """

    private fun filters(list: Int, language: Int, mangaOnly: Boolean = false) = FilterList(
        GalleryListFilter().apply { state = list },
        GalleryLanguageFilter().apply { state = language },
        EHentai.GenreGroup().apply { state.single { it.name == "Manga" }.state = mangaOnly },
    )
}
