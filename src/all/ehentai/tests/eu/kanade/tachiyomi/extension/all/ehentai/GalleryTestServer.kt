package eu.kanade.tachiyomi.extension.all.ehentai

import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class GalleryTestServer(
    private val imageCount: Int = 10,
    private val perDirectory: Int = 2,
    private val directoryDelayMillis: Long = 0,
    private val gate: CountDownLatch? = null,
    private val firstPageGate: CountDownLatch? = null,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newFixedThreadPool(8)
    val directories: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val imagePages: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val warningParameters: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val failurePage = AtomicInteger(-1)
    val invalidPage = AtomicInteger(-1)
    val pendingStarted = CountDownLatch(1)
    val pendingFinished = CountDownLatch(1)
    val firstPageStarted = CountDownLatch(1)
    val baseUrl = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val url = "$baseUrl${exchange.requestURI}".toHttpUrl()
            val image = url.pathSegments.first() == "s"
            val directory = url.queryParameter("p")?.toInt() ?: 0
            try {
                val html = if (image) {
                    val number = url.pathSegments.last().substringAfterLast('-').toInt()
                    imagePages += number
                    "<img id=\"img\" src=\"$baseUrl/images/$number.jpg\">"
                } else {
                    directories += directory
                    warningParameters += url.queryParameter("nw")
                    if (directory == 0) {
                        firstPageStarted.countDown()
                        firstPageGate?.await(5, TimeUnit.SECONDS)
                    } else {
                        pendingStarted.countDown()
                        gate?.await(5, TimeUnit.SECONDS)
                    }
                    Thread.sleep(directoryDelayMillis)
                    if (invalidPage.get() == directory) "<html>Temporarily unavailable</html>" else directoryHtml(directory)
                }.toByteArray()
                exchange.sendResponseHeaders(if (!image && directory == failurePage.get()) 503 else 200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
            } catch (_: IOException) {
                // Cancellation closes the client's connection while the gated handler is running.
            } finally {
                exchange.close()
                if (!image && directory > 0) pendingFinished.countDown()
            }
        }
        server.start()
    }

    private fun directoryHtml(page: Int): String {
        val images = (page * perDirectory + 1..minOf((page + 1) * perDirectory, imageCount))
            .joinToString("") { "<a href=\"/s/hash/1-$it\"></a>" }
        return """
            <h1 id="gn">Gallery</h1>
            <div id="gdd"><table>
              <tr><td class="gdt1">Posted:</td><td class="gdt2">2026-10-07 13:09</td></tr>
              <tr><td class="gdt1">Length:</td><td class="gdt2">$imageCount pages</td></tr>
            </table></div>
            <table class="ptt"><tr><td><a href="?p=${(imageCount - 1) / perDirectory}">Last</a></td></tr></table>
            <div id="gdt">$images</div>
        """.trimIndent()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
