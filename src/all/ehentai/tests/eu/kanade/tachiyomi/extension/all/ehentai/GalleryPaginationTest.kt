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
    fun abandonedSearchesAreBoundedAndEvictedResponsesCannotRestoreThem() {
        val oldest = source.searchMangaRequest(1, "query0", FilterList())
        parse(oldest, "https://exhentai.org/?next=0")
        for (index in 1 until 1000) {
            parse(source.searchMangaRequest(1, "query$index", FilterList()), "https://exhentai.org/?next=$index")
        }
        assertFalse(parse(oldest, "https://exhentai.org/?next=late").hasNextPage)
        assertThrows(IOException::class.java) { source.searchMangaRequest(2, "query0", FilterList()) }
        val retained = (0 until 1000).count { index ->
            runCatching { source.searchMangaRequest(2, "query$index", FilterList()) }.isSuccess
        }
        assertEquals(16, retained)
        assertEquals("999", source.searchMangaRequest(2, "query999", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun activelyUsedSearchSurvivesEvictionOfAbandonedSearches() {
        parse(source.searchMangaRequest(1, "active", FilterList()), "https://exhentai.org/?next=active")
        for (index in 0 until 1000) {
            parse(source.searchMangaRequest(1, "query$index", FilterList()), "https://exhentai.org/?next=$index")
            assertEquals("active", source.searchMangaRequest(2, "active", FilterList()).url.queryParameter("next"))
        }
    }

    @Test
    fun longSearchKeepsOnlyRecentPageCursorsIncludingTheCurrentRetry() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=2")
        for (page in 2..1000) {
            parse(source.searchMangaRequest(page, "query", FilterList()), "https://exhentai.org/?next=${page + 1}")
        }
        assertEquals("1000", source.searchMangaRequest(1000, "query", FilterList()).url.queryParameter("next"))
        assertEquals("1001", source.searchMangaRequest(1001, "query", FilterList()).url.queryParameter("next"))
        assertThrows(IOException::class.java) { source.searchMangaRequest(2, "query", FilterList()) }
        assertEquals(8, (2..1001).count { page ->
            runCatching { source.searchMangaRequest(page, "query", FilterList()) }.isSuccess
        })
    }

    @Test
    fun overlappingSearchesKeepTheirOwnCursorsWhenResponsesArriveOutOfOrder() {
        val first = source.searchMangaRequest(1, "first", FilterList())
        val second = source.searchMangaRequest(1, "second", FilterList())
        assertTrue(parse(second, "https://exhentai.org/?f_search=second&next=20&from=19").hasNextPage)
        assertTrue(parse(first, "https://exhentai.org/?f_search=first&next=10").hasNextPage)
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
        assertTrue(parse(refreshed, "https://exhentai.org/?next=new").hasNextPage)
        assertFalse(parse(old, "https://exhentai.org/?next=old").hasNextPage)
        assertEquals("new", source.searchMangaRequest(2, "query", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun refreshedSearchIgnoresALateSecondPageResponse() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=old2")
        val oldSecond = source.searchMangaRequest(2, "query", FilterList())
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=new2")
        parse(source.searchMangaRequest(2, "query", FilterList()), "https://exhentai.org/?next=new3")
        assertFalse(parse(oldSecond, "https://exhentai.org/?next=old3").hasNextPage)
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
    fun aLateRetryCannotOverwriteANewerCursorOrFinishTheSearch() {
        listOf("https://exhentai.org/?next=stale", null).forEach { staleNext ->
            parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=second")
            val firstAttempt = source.searchMangaRequest(2, "query", FilterList())
            val retry = source.searchMangaRequest(2, "query", FilterList())
            assertTrue(parse(retry, "https://exhentai.org/?next=new-third").hasNextPage)
            assertFalse(parse(firstAttempt, staleNext).hasNextPage)
            assertEquals("new-third", source.searchMangaRequest(3, "query", FilterList()).url.queryParameter("next"))
        }
    }

    @Test
    fun changingAnEarlierCursorInvalidatesLaterRequestsAndCursors() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=second")
        parse(source.searchMangaRequest(2, "query", FilterList()), "https://exhentai.org/?next=old-third")
        parse(source.searchMangaRequest(3, "query", FilterList()), "https://exhentai.org/?next=old-fourth")
        val oldFourth = source.searchMangaRequest(4, "query", FilterList())

        parse(source.searchMangaRequest(2, "query", FilterList()), "https://exhentai.org/?next=new-third")
        assertFalse(parse(oldFourth, "https://exhentai.org/?next=old-fifth").hasNextPage)
        assertThrows(IOException::class.java) { source.searchMangaRequest(4, "query", FilterList()) }
        assertEquals("new-third", source.searchMangaRequest(3, "query", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun retryingAnUnchangedCursorPreservesThePendingNextPage() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=second")
        parse(source.searchMangaRequest(2, "query", FilterList()), "https://exhentai.org/?next=third")
        val pendingThird = source.searchMangaRequest(3, "query", FilterList())
        parse(source.searchMangaRequest(2, "query", FilterList()), "https://exhentai.org/?next=third")
        assertTrue(parse(pendingThird, "https://exhentai.org/?next=fourth").hasNextPage)
        assertEquals("fourth", source.searchMangaRequest(4, "query", FilterList()).url.queryParameter("next"))
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
    fun completedSearchReleasesItsCursorsAndCanStartAgain() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=old")
        val lastPage = source.searchMangaRequest(2, "query", FilterList())
        assertFalse(parse(lastPage, null).hasNextPage)
        assertThrows(IOException::class.java) { source.searchMangaRequest(2, "query", FilterList()) }

        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=new")
        assertEquals("new", source.searchMangaRequest(2, "query", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun completingOneSearchPreservesOtherSearchesAndLatestPagination() {
        parse(source.searchMangaRequest(1, "completed", FilterList()), "https://exhentai.org/?next=last")
        parse(source.searchMangaRequest(1, "active", FilterList()), "https://exhentai.org/?next=active")
        htmlResponse(source.latestUpdatesRequest(1), listing("https://exhentai.org/?next=latest")).use {
            source.latestUpdatesParse(it)
        }

        parse(source.searchMangaRequest(2, "completed", FilterList()), null)
        assertEquals("active", source.searchMangaRequest(2, "active", FilterList()).url.queryParameter("next"))
        assertEquals("latest", source.latestUpdatesRequest(2).url.queryParameter("next"))
    }

    @Test
    fun oldFinalPageDoesNotRetireARefreshedSearch() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=old")
        val oldLastPage = source.searchMangaRequest(2, "query", FilterList())
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=new")

        parse(oldLastPage, null)
        assertEquals("new", source.searchMangaRequest(2, "query", FilterList()).url.queryParameter("next"))
    }

    @Test
    fun lateResponseCannotRestoreACompletedSearch() {
        parse(source.searchMangaRequest(1, "query", FilterList()), "https://exhentai.org/?next=last")
        val overlappingRequest = source.searchMangaRequest(2, "query", FilterList())
        val lastPage = source.searchMangaRequest(2, "query", FilterList())
        parse(lastPage, null)
        assertFalse(parse(overlappingRequest, "https://exhentai.org/?next=late").hasNextPage)

        assertThrows(IOException::class.java) { source.searchMangaRequest(3, "query", FilterList()) }
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
