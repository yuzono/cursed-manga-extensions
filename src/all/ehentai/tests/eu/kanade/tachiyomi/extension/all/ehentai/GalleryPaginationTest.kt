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
    fun abandonedSearchesAreBoundedWithoutEvictingActiveSearches() {
        val oldest = request(query = "old")
        respond(oldest, "old")
        respond(request(query = "active"), "active")
        repeat(1000) { index ->
            respond(request(query = "query$index"), "$index")
            assertEquals("active", cursor(query = "active"))
        }
        assertFalse(respond(oldest, "late"))
        assertThrows(IOException::class.java) { request(2, "old") }
        val retained = (0 until 1000).count { index ->
            runCatching { request(2, "query$index") }.isSuccess
        }
        assertEquals(15, retained)
        assertEquals("999", cursor(query = "query999"))
    }

    @Test
    fun longSearchRetainsRecentCursorsForRetries() {
        respond(request(), "2")
        for (page in 2..1000) respond(request(page), "${page + 1}")
        assertEquals("1000", cursor(1000))
        assertEquals("1001", cursor(1001))
        assertThrows(IOException::class.java) { request(2) }
        assertEquals(8, (2..1001).count { runCatching { request(it) }.isSuccess })
    }

    @Test
    fun overlappingSearchesFollowTheirOwnWebsiteCursors() {
        val first = request(query = "first")
        respond(first, "10")
        val second = request(query = "second")
        assertEquals("10", cursor(query = "first"))
        assertTrue(respond(second, "20", "19"))
        val next = request(2, "second").url
        assertEquals("second", next.queryParameter("f_search"))
        assertEquals("20", next.queryParameter("next"))
        assertEquals("19", next.queryParameter("from"))
        assertEquals("10", cursor(query = "first"))
    }

    @Test
    fun filtersKeepIndependentCursorsWhenResponsesArriveOutOfOrder() {
        val variants = listOf(
            filters(0, 0), filters(1, 0), filters(3, 0), filters(1, 1), filters(1, 1, true),
        )
        val requests = variants.map { request(list = it) }
        requests.withIndex().reversed().forEach { (index, request) ->
            respond(request, "$index")
        }
        variants.forEachIndexed { index, filters ->
            val next = request(2, list = filters)
            assertEquals("$index", next.url.queryParameter("next"))
            assertEquals(requests[index].url.encodedPath, next.url.encodedPath)
            assertEquals(requests[index].headers, next.headers)
        }
    }

    @Test
    fun refreshRejectsLateFirstSecondAndFinalPageResponses() {
        for (page in listOf(1, 2)) {
            for (lateCursor in listOf("stale", null)) {
                respond(request(), "old2")
                val old = request(page)
                respond(request(), "new2")
                assertFalse(respond(old, lateCursor))
                assertEquals("new2", cursor())
            }
        }
    }

    @Test
    fun retriesUseTheSamePageUrlAndRejectOlderResponses() {
        for (lateCursor in listOf("stale", null)) {
            respond(request(), "second")
            val firstAttempt = request(2)
            val retry = request(2)
            assertEquals(firstAttempt.url, retry.url)
            assertTrue(respond(retry, "third"))
            assertFalse(respond(firstAttempt, lateCursor))
            assertEquals("third", cursor(3))
            assertEquals(retry.url, request(2).url)
        }
    }

    @Test
    fun changingAnEarlierCursorInvalidatesLaterPages() {
        respond(request(), "second")
        respond(request(2), "old-third")
        respond(request(3), "old-fourth")
        val oldFourth = request(4)
        respond(request(2), "new-third")
        assertFalse(respond(oldFourth, "old-fifth"))
        assertThrows(IOException::class.java) { request(4) }
        assertEquals("new-third", cursor(3))
    }

    @Test
    fun unchangedCursorPreservesThePendingNextPage() {
        respond(request(), "second")
        respond(request(2), "third")
        val pending = request(3)
        respond(request(2), "third")
        assertTrue(respond(pending, "fourth"))
        assertEquals("fourth", cursor(4))
    }

    @Test
    fun completedSearchReleasesItsCursorsWithoutAffectingOtherLists() {
        val latest = source.latestUpdatesRequest(1)
        response(latest, "latest").use { source.latestUpdatesParse(it) }
        respond(request(query = "active"), "active")
        respond(request(), "last")
        val overlapping = request(2)
        assertFalse(respond(request(2), null))
        assertFalse(respond(overlapping, "late"))
        assertThrows(IOException::class.java) { request(2) }
        assertEquals("active", cursor(query = "active"))
        assertEquals("latest", source.latestUpdatesRequest(2).url.queryParameter("next"))
        respond(request(), "new")
        assertEquals("new", cursor())
        assertFalse(respond(request(), null))
        assertThrows(IOException::class.java) { request(2) }
    }

    @Test
    fun popularReturnsAFiniteGalleryList() {
        val request = source.popularMangaRequest(1)
        assertEquals("/popular", request.url.encodedPath)
        val result = response(request, null).use { source.popularMangaParse(it) }
        assertFalse(result.hasNextPage)
        assertEquals("Gallery", result.mangas.single().title)
        assertEquals("/g/1/abcdef/?nw=always", result.mangas.single().url)
    }

    private fun request(page: Int = 1, query: String = "query", list: FilterList = FilterList()) =
        source.searchMangaRequest(page, query, list)

    private fun cursor(page: Int = 2, query: String = "query") =
        request(page, query).url.queryParameter("next")

    private fun respond(request: Request, next: String?, from: String? = null): Boolean =
        response(request, next, from).use { source.searchMangaParse(it).hasNextPage }

    private fun response(request: Request, next: String?, from: String? = null) = htmlResponse(
        request,
        """
        <table class="itg"><tr><td><a href="/g/1/abcdef/">
          <div class="glink">Gallery</div></a></td></tr></table>
        """ + next?.let {
            val url = request.url.newBuilder().setQueryParameter("next", it)
            if (from != null) url.setQueryParameter("from", from)
            "<a id='unext' href='${url.build().toString().replace("&", "&amp;")}'>Next</a>"
        }.orEmpty(),
    )

    private fun filters(list: Int, language: Int, mangaOnly: Boolean = false) = FilterList(
        GalleryListFilter().apply { state = list },
        GalleryLanguageFilter().apply { state = language },
        EHentai.GenreGroup().apply { state.single { it.name == "Manga" }.state = mangaOnly },
    )
}
