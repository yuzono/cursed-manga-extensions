package eu.kanade.tachiyomi.extension.all.ehentai

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class GalleryApiTest {
    @Test
    fun everyListingEndpointUsesOneRequestWithoutLoadingDetailsOrImages() {
        ListingServer().use { server ->
            val source = TestSource(server.baseUrl)
            val actions = listOf(
                { source.fetchPopularManga(1) },
                { source.fetchLatestUpdates(1) },
                { source.fetchSearchManga(1, "query", filters(0)) },
                { source.fetchSearchManga(1, "query", filters(1)) },
                { source.fetchSearchManga(1, "query", filters(2)) },
            )
            actions.forEachIndexed { index, action ->
                val result = action().toBlocking().single()
                assertEquals(index + 1, server.requests.size)
                assertEquals(40, result.mangas.size)
                assertTrue(result.mangas.all { it.thumbnail_url?.endsWith(".jpg") == true })
                if (index == 0) assertFalse(result.hasNextPage) else assertTrue(result.hasNextPage)
            }
            assertEquals(listOf("/popular", "/", "/", "/watched", "/favorites.php"), server.requests.map { it.encodedPath })
            assertEquals(null, server.requests[0].query)
        }
    }

    @Test
    fun watchedAndFavoritesPaginateAndRefreshWithoutReturningStaleResults() {
        ListingServer().use { server ->
            val source = TestSource(server.baseUrl)
            listOf(1, 2).forEach { list ->
                val filters = filters(list)
                source.fetchSearchManga(1, "artist:example", filters).toBlocking().single()
                source.fetchSearchManga(2, "artist:example", filters).toBlocking().single()
                val secondPage = server.requests.last()
                assertEquals("cursor", secondPage.queryParameter("next"))
                assertEquals("artist:example language:chinese", secondPage.queryParameter("f_search"))
                val refreshed = source.fetchSearchManga(1, "artist:example", filters).toBlocking().single()
                assertEquals("Gallery ${server.requests.size}-0", refreshed.mangas.first().title)
                assertEquals(null, server.requests.last().queryParameter("next"))
            }
            assertEquals(6, server.requests.size)
        }
    }

    @Test
    fun failedSearchCanBeRetriedWithoutExtraRequestsOrStaleCursors() {
        ListingServer().use { server ->
            val source = TestSource(server.baseUrl)
            val filters = filters(1)
            source.fetchSearchManga(1, "", filters).toBlocking().single()
            server.fail = true
            assertThrows(RuntimeException::class.java) { source.fetchSearchManga(2, "", filters).toBlocking().single() }
            server.fail = false
            val retried = source.fetchSearchManga(2, "", filters).toBlocking().single()
            assertEquals("Gallery 3-0", retried.mangas.first().title)
            assertEquals(server.requests[1], server.requests[2])
            assertEquals(3, server.requests.size)
        }
    }

    private fun filters(list: Int) = FilterList(
        GalleryListFilter().apply { state = list },
        GalleryLanguageFilter().apply { state = values.indexOf("Chinese") },
        EHentai.GenreGroup(),
    )

    private class ListingServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<HttpUrl>()
        var fail = false
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                val url = "$baseUrl${exchange.requestURI}".toHttpUrl()
                requests += url
                val next = url.newBuilder().setQueryParameter("next", "cursor").build().toString().replace("&", "&amp;")
                val rows = (0 until 40).joinToString("") { index ->
                    """<tr><td class="glthumb"><img src="/thumb/$index.jpg"></td>
                        <td><a href="/g/$index/token/"><div class="glink">Gallery ${requests.size}-$index</div></a></td></tr>"""
                }
                val bytes = "<table class='itg'>$rows</table><a id='unext' href='$next'>Next</a>".toByteArray()
                exchange.sendResponseHeaders(if (fail) 503 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
