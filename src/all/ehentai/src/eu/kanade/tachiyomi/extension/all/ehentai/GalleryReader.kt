package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

internal fun Element.galleryImagePages(): List<String> = select("#gdt a").map { it.absUrl("href") }

internal fun Document.galleryReaderPages(): List<Page> {
    val firstImages = galleryImagePages()
    check(firstImages.isNotEmpty()) { "No image pages found" }
    val pageCount = select("#gdd tr").firstOrNull { it.selectFirst(".gdt1")?.text() == "Length:" }
        ?.selectFirst(".gdt2")?.text()?.substringBefore(' ')?.replace(",", "")?.toIntOrNull()
        ?: error("Gallery page count not found")
    check(pageCount >= firstImages.size) { "Invalid gallery page count" }
    val galleryUrl = location().toHttpUrl()
    val directories = (0..(pageCount - 1) / firstImages.size).map { page ->
        galleryUrl.newBuilder().setQueryParameter("p", page.toString()).build().toString()
    }
    // Page.index and Page.url are persisted by the reader, so later pages also work after a restart.
    return List(pageCount) { index ->
        Page(index, firstImages.getOrNull(index) ?: directories[index / firstImages.size])
    }
}

internal fun Element.galleryImagePage(index: Int): String = selectFirst("#gdt a[href$=-${index + 1}]")?.absUrl("href")
    ?: error("Image page ${index + 1} not found; refresh the chapter")

private val reloadKeyPattern = Regex("nl\\('(.+?)'\\)")

internal fun Element.galleryImageUrl(pageUrl: HttpUrl, originalImage: Boolean, reloadOnFailure: Boolean): String {
    val imageUrl = selectFirst("#img")?.absUrl("src").orEmpty()
    val reloadKey = reloadKeyPattern.find(selectFirst("#loadfail")?.attr("onclick").orEmpty())?.groupValues?.get(1)
    if (originalImage) {
        val originalUrl = selectFirst("a[href*=/fullimg/]")?.absUrl("href")
        if (!originalUrl.isNullOrEmpty()) {
            return originalUrl.toHttpUrl().newBuilder().addQueryParameter("nl", reloadKey).build().toString()
        }
    }
    check(imageUrl.isNotBlank()) { "Image URL not found" }
    if (!reloadOnFailure || reloadKey.isNullOrEmpty()) return imageUrl
    val reloadUrl = pageUrl.newBuilder().setQueryParameter("nl", reloadKey).build()
    return "$imageUrl#$reloadUrl"
}

internal fun OkHttpClient.Builder.addGalleryImageRetry(
    headers: () -> Headers,
    parseImage: (Response) -> String,
): OkHttpClient.Builder = addInterceptor { chain ->
    val request = chain.request()
    val result = try {
        Result.success(chain.proceed(request))
    } catch (e: IOException) {
        Result.failure(e)
    }
    val reloadUrl = request.url.fragment
    if (reloadUrl == null || result.getOrNull()?.isSuccessful == true || chain.call().isCanceled()) {
        return@addInterceptor result.getOrThrow()
    }
    result.getOrNull()?.close()
    val imageUrl = chain.proceed(GET(reloadUrl, headers())).use { reload ->
        if (!reload.isSuccessful) throw IOException("HTTP ${reload.code}")
        try {
            parseImage(reload)
        } catch (e: IllegalStateException) {
            throw IOException(e.message, e)
        }
    }
    chain.proceed(request.newBuilder().url(imageUrl).build())
}
