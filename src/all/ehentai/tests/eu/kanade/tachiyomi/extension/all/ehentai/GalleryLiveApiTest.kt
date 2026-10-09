package eu.kanade.tachiyomi.extension.all.ehentai

import keiyoushi.network.addCookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GalleryLiveApiTest {
    @Test
    fun galleryDirectoryAndBothImageServersReturnReadableData() {
        assumeTrue(System.getenv("EXHENTAI_LIVE_TESTS") == "true")
        val client = OkHttpClient.Builder()
            .addCookie({ "e-hentai.org" }, listOf("uconfig" to "prn_n", "nw" to "1"))
            .build()
        val galleryUrl = "https://e-hentai.org/g/3176518/12d331b200/"
        val imagePages = mutableListOf<String>()
        var next: String? = galleryUrl
        var galleryPages = 0
        while (next != null) {
            client.newCall(Request.Builder().url(next).build()).execute().use { response ->
                assertEquals(200, response.code)
                val document = Jsoup.parse(response.body.string(), response.request.url.toString())
                imagePages += document.galleryImagePages()
                next = document.nextGalleryPageUrl()
                galleryPages++
            }
        }
        assertEquals(7, galleryPages)
        assertEquals(135, imagePages.size)
        assertEquals(135, imagePages.distinct().size)

        val imageUrl = client.newCall(Request.Builder().url(imagePages.first()).build()).execute().use { response ->
            assertEquals(200, response.code)
            Jsoup.parse(response.body.string(), response.request.url.toString())
                .galleryImageUrl(response.request.url, originalImage = false, reloadOnFailure = true)
                .toHttpUrl()
        }
        val normalUrl = imageUrl.newBuilder().fragment(null).build()
        checkImage(client, normalUrl.toString())
        val reloadUrl = imageUrl.fragment!!
        val reloadedImageUrl = client.newCall(Request.Builder().url(reloadUrl).build()).execute().use { response ->
            assertEquals(200, response.code)
            Jsoup.parse(response.body.string(), response.request.url.toString())
                .galleryImageUrl(response.request.url, originalImage = false, reloadOnFailure = false)
        }
        checkImage(client, reloadedImageUrl)
        println("Live gallery API: 7 directory pages, 135 unique image pages; primary and reload image servers returned images.")
    }

    private fun checkImage(client: OkHttpClient, url: String) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"))
            val signature = response.body.source().readByteArray(12)
            assertEquals("RIFF", signature.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WEBP", signature.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        }
    }
}
