package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.blackholeSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

class GalleryLiveApiTest {
    @Test
    fun categoryFilterIsAppliedByTheLiveWebsite() {
        assumeTrue(System.getenv("EXHENTAI_LIVE_TESTS") == "true")
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val client = liveClient(requests)
        val source = TestSource("https://e-hentai.org", client)
        val filters = source.getFilterList()
        filters.filterIsInstance<EHentai.GenreGroup>().single().state.single { it.name == "Manga" }.state = true
        client.newCall(source.searchMangaRequest(1, "", filters)).execute().use { response ->
            assertEquals(200, response.code)
            val document = response.asJsoup()
            val categories = document.select(".itg .cn, .itg .cs").map { it.text() }
            assertTrue(categories.isNotEmpty())
            assertEquals(setOf("Manga"), categories.toSet())
            println("LIVE_CATEGORY requested=Manga categories=${categories.toSet()} galleries=${GalleryList(document).galleries.size}")
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun firstImageAndLastPageLoadWithoutFetchingIntermediateDirectories() {
        assumeTrue(System.getenv("EXHENTAI_LIVE_TESTS") == "true")
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val client = liveClient(requests)
        val galleryUrl = "https://e-hentai.org/g/3176518/12d331b200/"
        val expected = (0..6).flatMap { page ->
            client.newCall(Request.Builder().url("$galleryUrl?p=$page").build()).execute().use { response ->
                assertEquals(200, response.code)
                response.asJsoup().galleryImagePages()
            }
        }
        assertEquals(135, expected.size)
        assertEquals(135, expected.distinct().size)
        requests.clear()

        val openingStart = System.nanoTime()
        val source = TestSource("https://e-hentai.org", client)
        val manga = SManga.create().apply { url = "/g/3176518/12d331b200/" }
        assertTrue(source.fetchMangaDetails(manga).toBlocking().single().initialized)
        val chapter = source.fetchChapterList(manga).toBlocking().single().single()
        assertTrue(chapter.date_upload > 0)
        val readerStart = System.nanoTime()
        val pages = source.fetchPageList(chapter).toBlocking().single()
        val indexMillis = millisSince(readerStart)
        assertEquals((0 until 135).toList(), pages.map { it.index })
        assertEquals(expected.take(20), pages.take(20).map { it.url })
        assertEquals(1, requests.count { it.toHttpUrl().encodedPath.startsWith("/g/") })

        val imageStart = System.nanoTime()
        val imageUrl = source.fetchImageUrl(pages.first()).toBlocking().single()
        val imageUrlMillis = millisSince(imageStart)
        val downloadStart = System.nanoTime()
        val firstImageBytes = checkImage(client, imageUrl)
        val downloadMillis = millisSince(downloadStart)
        val openingMillis = millisSince(openingStart)
        assertEquals(1, requests.count { it.toHttpUrl().encodedPath.startsWith("/g/") })
        println("LIVE_OPEN pages=135 directoryRequestsBeforeFirstImage=1 readerIndexMs=$indexMillis firstImageUrlMs=$imageUrlMillis fullImageDownloadMs=$downloadMillis fullImageBytes=$firstImageBytes galleryToFirstImageNetworkMs=$openingMillis")

        val jumpStart = System.nanoTime()
        source.fetchImageUrl(pages.last()).toBlocking().single()
        val directories = requests.filter { it.toHttpUrl().encodedPath.startsWith("/g/") }.map { it.toHttpUrl().queryParameter("p")?.toInt() ?: 0 }
        assertEquals(listOf(0, 6), directories)
        assertTrue(requests.any { it.substringBefore('?') == expected.last() })
        println("LIVE_JUMP targetPage=135 directoryPages=$directories imageUrlMs=${millisSince(jumpStart)}")

        val reloadUrl = imageUrl.toHttpUrl().fragment!!
        val reloadedImageUrl = client.newCall(Request.Builder().url(reloadUrl).build()).execute().use { response ->
            assertEquals(200, response.code)
            response.asJsoup().galleryImageUrl(response.request.url, originalImage = false, reloadOnFailure = false)
        }
        println("LIVE_RELOAD fullImageBytes=${checkImage(client, reloadedImageUrl)}")
    }

    @Test
    fun popularLatestAndFilteredSearchUseTheirActualWebsiteEndpoints() {
        assumeTrue(System.getenv("EXHENTAI_LIVE_TESTS") == "true")
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val source = TestSource("https://e-hentai.org", liveClient(requests))
        val actions = listOf(
            "popular" to { source.fetchPopularManga(1) },
            "popular-filter-entry" to { source.fetchSearchManga(1, "", FilterList(GalleryListFilter().apply { state = 2 })) },
            "latest" to { source.fetchLatestUpdates(1) },
            "language-search" to { source.fetchSearchManga(1, "", FilterList(GalleryLanguageFilter().apply { state = values.indexOf("中文") })) },
        )
        actions.forEach { (name, action) ->
            requests.clear()
            val start = System.nanoTime()
            val result = action().toBlocking().single()
            assertTrue("$name must return galleries", result.mangas.isNotEmpty())
            assertEquals(1, requests.size)
            if (name.startsWith("popular")) {
                assertEquals("/popular", requests.single().toHttpUrl().encodedPath)
                assertFalse(result.hasNextPage)
            }
            println("LIVE_LIST endpoint=$name requests=${requests.size} galleries=${result.mangas.size} elapsedMs=${millisSince(start)}")
        }
    }

    private fun liveClient(requests: MutableList<String>) = OkHttpClient.Builder()
        .addGalleryCookies { _, _ -> GalleryCredentials("", "", "") }
        .addGalleryImageRetry({ Headers.headersOf("Referer", "https://e-hentai.org/") }) { response ->
            response.asJsoup().galleryImageUrl(response.request.url, false, false)
        }
        .addNetworkInterceptor { chain ->
            requests += chain.request().url.newBuilder().fragment(null).build().toString()
            chain.proceed(chain.request())
        }
        .build()

    private fun checkImage(client: OkHttpClient, url: String): Long = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        assertEquals(200, response.code)
        assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"))
        val source = response.body.source()
        val signature = source.readByteArray(12)
        assertEquals("RIFF", signature.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WEBP", signature.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        12 + source.readAll(blackholeSink())
    }

    private fun millisSince(start: Long) = (System.nanoTime() - start) / 1_000_000
}
