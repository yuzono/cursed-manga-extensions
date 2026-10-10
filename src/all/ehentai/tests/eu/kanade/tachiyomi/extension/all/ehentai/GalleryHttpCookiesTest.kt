package eu.kanade.tachiyomi.extension.all.ehentai

import com.sun.net.httpserver.HttpServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class GalleryHttpCookiesTest {
    @Test
    fun redirectsStripGalleryCredentialsAndPreserveImageHostCookies() {
        CookieServer().use { server ->
            val imageCookies = cookieJar { url ->
                if (url.host == "images.test") {
                    mapOf("image_session" to "image-cookie")
                } else {
                    emptyMap()
                }
            }
            for (jar in listOf(CookieJar.NO_COOKIES, imageCookies)) {
                val client = client().cookieJar(jar).addGalleryCookies { _, _ ->
                    GalleryCredentials("member", "pass", "session", mapOf("sk" to "profile"))
                }.build()
                for (host in listOf("e-hentai.org", "exhentai.org")) {
                    server.cookies.clear()
                    server.get(client, host, "/redirect")
                    assertEquals(2, server.cookies.size)
                    assertTrue("ipb_pass_hash=pass" in server.cookies[0])
                    val expected =
                        if (jar === CookieJar.NO_COOKIES) "" else "image_session=image-cookie"
                    assertEquals(expected, server.cookies[1])
                }
            }
        }
    }

    @Test
    fun requestsRefreshSessionsAndKeepWebViewSettingsWithoutDuplicateCookies() {
        CookieServer().use { server ->
            var session = "first"
            var config = "dm_e-tf_0"
            var reads = 0
            val client = client().cookieJar(cookieJar {
                mapOf(
                    "cf_clearance" to "clearance", "sk" to "profile", "uconfig" to config,
                    "igneous" to "jar-session",
                )
            }).addGalleryCookies { forceEh, _ ->
                reads++
                val site = if (forceEh) "eh" else "ex"
                GalleryCredentials("$site-member", "$site-pass", session)
            }.build()
            assertEquals(0, reads)
            for (value in listOf("first", "refreshed", "")) {
                session = value
                val cookies = server.get(client)
                for ((key, expected) in mapOf(
                    "cf_clearance" to "clearance", "sk" to "profile", "uconfig" to config,
                    "nw" to "1", "ipb_member_id" to "ex-member", "ipb_pass_hash" to "ex-pass",
                    "igneous" to value.ifEmpty { "jar-session" },
                )) {
                    val matching = cookies.filter { it.startsWith("$key=") }
                    assertEquals(listOf("$key=$expected"), matching)
                }
            }
            assertTrue("ipb_member_id=eh-member" in server.get(client, "e-hentai.org"))
            assertEquals(4, reads)
            config = "prn_n"
            assertTrue(server.get(client).none { it.startsWith("uconfig=") })
            val unrelated = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
            assertEquals(listOf(""), server.get(unrelated, "images.test"))
            assertEquals(5, reads)
        }
    }

    @Test
    fun webViewProfileSettingsReachTheServerWithoutACookieJar() {
        CookieServer().use { server ->
            val cookies = "ipb_member_id=member; ipb_pass_hash=pass; sk=profile; uconfig=dm_e-tf_0"
            val client = client().addGalleryCookies { forceEh, requestCookie ->
                galleryCredentials(forceEh, { cookies }, requestCookie) { "" }
            }.build()
            val received = server.get(client, "e-hentai.org", "/popular")
            assertTrue(received.containsAll(listOf("sk=profile", "uconfig=dm_e-tf_0")))
        }
    }

    private fun client() = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .dns { listOf(InetAddress.getByName("127.0.0.1")) }

    private fun cookieJar(values: (HttpUrl) -> Map<String, String>) = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl) = values(url).map { (name, value) ->
            Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).build()
        }
    }

    private class CookieServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val cookies = mutableListOf<String>()

        init {
            server.createContext("/") { exchange ->
                cookies += exchange.requestHeaders.getFirst("Cookie").orEmpty()
                if (exchange.requestURI.path == "/redirect") {
                    val target = "http://images.test:${server.address.port}/image"
                    exchange.responseHeaders.add("Location", target)
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    exchange.sendResponseHeaders(200, -1)
                }
                exchange.close()
            }
            server.start()
        }

        fun get(
            client: OkHttpClient,
            host: String = "exhentai.org",
            path: String = "/",
        ): List<String> {
            val request = Request.Builder().url("http://$host:${server.address.port}$path").build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            return cookies.last().split("; ")
        }

        override fun close() = server.stop(0)
    }
}
