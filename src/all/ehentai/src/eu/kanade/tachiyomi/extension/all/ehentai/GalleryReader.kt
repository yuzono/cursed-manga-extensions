package eu.kanade.tachiyomi.extension.all.ehentai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element

internal fun Element.galleryImagePages(): List<String> = select("#gdt a").map { it.absUrl("href") }

internal fun Element.nextGalleryPageUrl(): String? = select("a[onclick=return false]").last()
    ?.takeIf { it.text() == ">" }
    ?.absUrl("href")

internal fun Element.galleryImageUrl(pageUrl: HttpUrl, originalImage: Boolean, reloadOnFailure: Boolean): String {
    val imageUrl = select("#img").attr("abs:src")
    val reloadKey = Regex("nl\\('(.+?)'\\)").find(selectFirst("#loadfail")?.attr("onclick").orEmpty())?.groupValues?.get(1)
    if (originalImage) {
        val originalUrl = selectFirst("a[href*=/fullimg/]")?.absUrl("href")
        if (!originalUrl.isNullOrEmpty()) {
            return originalUrl.toHttpUrl().newBuilder().addQueryParameter("nl", reloadKey).build().toString()
        }
    }
    if (!reloadOnFailure || reloadKey.isNullOrEmpty()) return imageUrl
    val reloadUrl = pageUrl.newBuilder().setQueryParameter("nl", reloadKey).build()
    return "$imageUrl#$reloadUrl"
}
