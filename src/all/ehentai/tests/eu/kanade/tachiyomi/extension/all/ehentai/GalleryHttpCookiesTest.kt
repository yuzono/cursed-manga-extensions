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
    fun crossOriginRedirectsDoNotForwardGalleryCredentialsOrRemoveImageHostCookies() {
        val received = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received += exchange.requestHeaders.getFirst("Cookie").orEmpty()
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", "http://images.test:${server.address.port}/image")
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            val imageCookies = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl) = if (url.host == "images.test") {
                    listOf(Cookie.Builder().name("image_session").value("image-cookie").hostOnlyDomain(url.host).build())
                } else {
                    emptyList()
                }
            }
            for (jar in listOf(CookieJar.NO_COOKIES, imageCookies)) {
                val client = OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                    .cookieJar(jar)
                    .addGalleryCookies { _, _ -> GalleryCredentials("test-member", "test-pass", "test-session", mapOf("sk" to "profile")) }
                    .build()
                for (host in listOf("e-hentai.org", "exhentai.org")) {
                    received.clear()
                    client.newCall(Request.Builder().url("http://$host:${server.address.port}/redirect").build())
                        .execute().use { assertEquals(200, it.code) }
                    assertEquals(2, received.size)
                    assertTrue("ipb_pass_hash=test-pass" in received[0])
                    assertEquals(if (jar === CookieJar.NO_COOKIES) "" else "image_session=image-cookie", received[1])
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun requestsKeepWebViewCookiesAndRefreshTheGallerySession() {
        val receivedCookies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            receivedCookies += exchange.requestHeaders.getFirst("Cookie").orEmpty()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            var session = "first-session"
            var config = "dm_e-tf_0"
            var galleryCredentialReads = 0
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl) = listOf(
                        Cookie.Builder().name("cf_clearance").value("webview-clearance").domain(url.host).build(),
                        Cookie.Builder().name("sk").value("webview-settings").domain(url.host).build(),
                        Cookie.Builder().name("uconfig").value(config).domain(url.host).build(),
                        Cookie.Builder().name("igneous").value("jar-session").domain(url.host).build(),
                    )
                })
                .addGalleryCookies { forceEh, _ ->
                    galleryCredentialReads++
                    val site = if (forceEh) "eh" else "ex"
                    GalleryCredentials("$site-member", "$site-pass", session)
                }
                .build()
            assertEquals(0, galleryCredentialReads)
            val request = Request.Builder().url("http://exhentai.org:${server.address.port}/watched").build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            session = "refreshed-session"
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertTrue(receivedCookies.all { "cf_clearance=webview-clearance" in it && "sk=webview-settings" in it })
            assertTrue("igneous=first-session" in receivedCookies[0])
            assertTrue("igneous=refreshed-session" in receivedCookies[1])
            assertTrue(receivedCookies.all { "uconfig=dm_e-tf_0" in it && "nw=1" in it })
            assertTrue(receivedCookies.all { "ipb_member_id=ex-member" in it && "ipb_pass_hash=ex-pass" in it })
            assertTrue(receivedCookies.all { cookie -> cookie.split("; ").count { it.startsWith("igneous=") } == 1 })

            session = ""
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertTrue("igneous=jar-session" in receivedCookies[2])

            client.newCall(request.newBuilder().url("http://e-hentai.org:${server.address.port}/watched").build())
                .execute().use { assertEquals(200, it.code) }
            assertTrue("ipb_member_id=eh-member" in receivedCookies[3])
            assertEquals(4, galleryCredentialReads)

            config = "prn_n"
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertTrue("uconfig=" !in receivedCookies[4])

            var credentialReads = 0
            val unrelatedClient = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                .addGalleryCookies { _, _ ->
                    credentialReads++
                    GalleryCredentials("test-member", "test-pass", "ex-session")
                }
                .build()
            val initializationReads = credentialReads
            val imageRequest = request.newBuilder().url("http://images.test:${server.address.port}/image").build()
            unrelatedClient.newCall(imageRequest).execute().use { assertEquals(200, it.code) }
            assertEquals("", receivedCookies[5])
            assertEquals(initializationReads, credentialReads)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun webViewProfileSettingsReachTheServerWithoutACookieJar() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var cookieHeader = ""
        server.createContext("/") { exchange ->
            cookieHeader = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                .addGalleryCookies { forceEh, requestCookie ->
                    galleryCredentials(forceEh, { "ipb_member_id=member; ipb_pass_hash=pass; sk=active-profile; uconfig=dm_e-tf_0" }, requestCookie) { "" }
                }
                .build()
            client.newCall(Request.Builder().url("http://e-hentai.org:${server.address.port}/popular").build())
                .execute().use { assertEquals(200, it.code) }
            assertTrue("sk=active-profile" in cookieHeader)
            assertTrue("uconfig=dm_e-tf_0" in cookieHeader)
        } finally {
            server.stop(0)
        }
    }
}
