package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import java.io.IOException

internal class GalleryList(document: Document) {
    val nextPageUrl: String? = document.selectFirst("a#unext[href]")
        ?.takeIf { it.attr("href").isNotBlank() }
        ?.absUrl("href")
        ?.takeIf(String::isNotBlank)

    val galleries: List<SManga> = document.select(".itg .glink").map { titleElement ->
        val galleryLink = titleElement.closest("a")!!
        val gallery = titleElement.closest("tr, .gl1t")!!
        SManga.create().apply {
            title = titleElement.text()
            val artists = gallery.select("[title^=artist:]").map { it.attr("title").removePrefix("artist:") }.distinct()
            author = galleryCreator(title, artists)
            artist = author
            url = ExGalleryMetadata.normalizeUrl(galleryLink.absUrl("href").toHttpUrl().encodedPath)
            thumbnail_url = gallery.selectFirst(".glthumb img, .gl1e img, .gl3t img")?.let {
                it.attr("data-src").nullIfBlank() ?: it.absUrl("src").nullIfBlank()
            }
        }
    }
}

internal class GalleryPagination {
    private class Cursor(val url: String) {
        var request: PageRequest? = null
    }

    private class Session(firstPage: String) {
        // Browsing only needs recent cursors for retries, not every previously loaded page.
        val pages = object : LinkedHashMap<Int, Cursor>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Cursor>) = size > 8
        }.apply { put(1, Cursor(firstPage)) }
    }
    private class PageRequest(val key: String, val session: Session, val page: Int)

    private val sessions = object : LinkedHashMap<String, Session>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>): Boolean {
            if (size <= 16) return false
            eldest.value.pages.clear()
            return true
        }
    }

    @Synchronized
    fun request(firstPage: Request, page: Int): Request {
        val key = firstPage.url.toString()
        val session = if (page == 1) {
            Session(key).also { sessions.put(key, it)?.pages?.clear() }
        } else {
            sessions[key]
        }
        val cursor = session?.pages?.get(page)
            ?: throw IOException("No next page cursor; refresh the list")
        val request = PageRequest(key, session, page)
        cursor.request = request
        return firstPage.newBuilder()
            .url(cursor.url)
            .tag(PageRequest::class.java, request)
            .build()
    }

    @Synchronized
    fun update(request: Request, nextPageUrl: String?): Boolean {
        val page = request.tag(PageRequest::class.java) ?: return false
        // Refreshes and retries invalidate older responses, including within the same session.
        if (sessions[page.key] !== page.session || page.session.pages[page.page]?.request !== page) return false
        page.session.pages[page.page]?.request = null
        if (nextPageUrl == null) {
            // Retire only exhausted searches; interleaved searches may still be paged.
            sessions.remove(page.key)
            page.session.pages.clear()
        } else if (page.session.pages[page.page + 1]?.url != nextPageUrl) {
            page.session.pages.keys.removeAll { it > page.page }
            page.session.pages[page.page + 1] = Cursor(nextPageUrl)
        }
        return nextPageUrl != null
    }
}

internal class GalleryListFilter :
    Filter.Select<String>(
        "Gallery list",
        arrayOf("All galleries", "Watched tags", "Favorites"),
    ) {
    val path: String
        get() = when (state) {
            1 -> "watched"
            2 -> "favorites.php"
            else -> ""
        }
}

private val galleryLanguages = arrayOf(
    "All languages" to null,
    "Japanese" to "japanese",
    "English" to "english",
    "Chinese" to "chinese",
    "Korean" to "korean",
    "French" to "french",
    "German" to "german",
    "Spanish" to "spanish",
    "Portuguese" to "portuguese",
    "Italian" to "italian",
    "Dutch" to "dutch",
    "Russian" to "russian",
    "Polish" to "polish",
    "Hungarian" to "hungarian",
    "Thai" to "thai",
    "Vietnamese" to "vietnamese",
)

internal class GalleryLanguageFilter :
    Filter.Select<String>(
        "Language",
        galleryLanguages.map { it.first }.toTypedArray(),
    ) {
    fun addToQuery(query: String): String = galleryLanguages[state].second
        ?.let { "$query language:$it".trim() }
        ?: query
}
