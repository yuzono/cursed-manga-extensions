package eu.kanade.tachiyomi.extension.all.pururin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// Concrete so the generated sources keep this class name, which Komikku/SY match their delegated Pururin on
@Source
class Pururin(
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    override val id: Long,
) : KeiSource() {
    private val searchLang: Pair<String, String>?
        get() = when (lang) {
            "en" -> Pair("13010", "english")
            "ja" -> Pair("13011", "japanese")
            else -> null
        }

    private val langPath: String
        get() = when (lang) {
            "en" -> "/tags/language/13010/english"
            "ja" -> "/tags/language/13011/japanese"
            else -> ""
        }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (chain.request().tag(LegacyMarkup::class.java) == null || !response.isSuccessful) return@addInterceptor response

        val contentType = response.body.contentType()
        val document = response.asJsoup().apply { toLegacyMarkup(response.request.url.encodedPath) }
        response.newBuilder().body(document.outerHtml().toResponseBody(contentType)).build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = client.get("$baseUrl/browse$langPath?sort=most-popular&page=$page", Fetch.DOCUMENT).parseMangaList()

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get("$baseUrl/browse$langPath?page=$page", Fetch.DOCUMENT).parseMangaList()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val includeTags = mutableListOf<Pair<String, String>>()
        val excludeTags = mutableListOf<Pair<String, String>>()

        searchLang?.let { includeTags.add(it) }

        val sortBy = filters.firstInstanceOrNull<SelectFilter>()?.getValue() ?: "most-popular"

        val (pagesMin, pagesMax) = filters.firstInstanceOrNull<PageFilter>()?.state
            ?.takeIf(String::isNotEmpty)
            ?.let { parsePageRange(it) }
            ?: (1 to 9999)

        filters.firstInstanceOrNull<TypeFilter>()?.state
            ?.filterNot { it.state }
            ?.mapTo(excludeTags) { Pair(it.value, "${it.name} [Category]") }

        filters.filterIsInstance<TextFilter>().forEach { filter ->
            filter.state.split(",").filter(String::isNotBlank).forEach { tag ->
                val trimmed = tag.trim()
                val tagInfo = tagSearch(trimmed.lowercase().removePrefix("-"), filter.type) ?: return@forEach
                if (trimmed.startsWith('-')) excludeTags.add(tagInfo) else includeTags.add(tagInfo)
            }
        }

        if (query.isEmpty()) {
            when {
                // Searching with just one tag usually gives wrong results
                excludeTags.size == 1 && includeTags.isEmpty() -> excludeTags.add(excludeTags.first())

                includeTags.size == 1 && excludeTags.isEmpty() -> {
                    val url = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegments("browse/tags/content")
                        addPathSegment(includeTags[0].first)
                        addQueryParameter("sort", sortBy)
                        addQueryParameter("start_page", pagesMin.toString())
                        addQueryParameter("last_page", pagesMax.toString())
                        if (page > 1) addQueryParameter("page", page.toString())
                    }.build()
                    return client.get(url, Fetch.DOCUMENT).parseMangaList()
                }
            }
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addQueryParameter("q", query)
            addQueryParameter("sort", sortBy)
            addQueryParameter("start_page", pagesMin.toString())
            addQueryParameter("last_page", pagesMax.toString())
            if (includeTags.isNotEmpty()) addQueryParameter("included_tags", includeTags.toValue())
            if (excludeTags.isNotEmpty()) addQueryParameter("excluded_tags", excludeTags.toValue())
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return client.get(url, Fetch.DOCUMENT).parseMangaList()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "gallery" || url.pathSegments.getOrNull(1)?.toIntOrNull() == null) return null

        val response = client.get(url, Fetch.DOCUMENT)
        // Stored like browse links, so a pasted link doesn't add a second library entry
        val canonicalUrl = response.request.url.toString().removeSuffix("/")
        return parseDetails(response.asJsoup()).apply { setUrlWithoutDomain(canonicalUrl) }
    }

    // Details and chapters come from the same gallery page, so it is fetched once and both flags are ignored.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        val details = parseDetails(document).apply { url = manga.url }

        val chapterList = document.parseChapters().ifEmpty {
            chapters.ifEmpty {
                listOf(
                    SChapter.create().apply {
                        url = manga.url
                        name = "Chapter"
                    },
                )
            }
        }

        return SMangaUpdate(details, chapterList)
    }

    override val supportsRelatedMangas = true

    // The gallery page lists a few similar galleries below the comments, a different pick on every load
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        .select(".row-gallery-small a.card-gallery")
        .mapNotNull { it.toSMangaOrNull() }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$baseUrl${chapter.url}", Fetch.DOCUMENT).asJsoup()
        .select(".gallery-preview a img")
        .mapIndexed { i, img ->
            val imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            Page(i, imageUrl = imageUrl.replace("t.", "."))
        }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    private fun Response.parseMangaList(): MangasPage {
        val doc = asJsoup()

        val mangas = doc.select("a.card").mapNotNull { it.toSMangaOrNull() }
        val hasNextPage = doc.selectFirst(".page-item [rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSMangaOrNull(): SManga? {
        val cardTitle = attrOrNull("title") ?: return null
        return SManga.create().apply {
            title = cardTitle
            setUrlWithoutDomain(absUrl("href"))
            thumbnail_url = select("img").attr("abs:src")
        }
    }

    private fun Document.parseChapters(): List<SChapter> = select(".table-collection tbody tr a")
        .mapNotNull { element ->
            SChapter.create().apply {
                name = element.textOrNull() ?: return@mapNotNull null
                val href = element.absUrl("href").ifEmpty { return@mapNotNull null }
                setUrlWithoutDomain(href)
            }
        }
        .reversed()

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        val gallery = document.select(".box-gallery")

        title = gallery.select(".title").text().ifEmpty { error("Title not found") }
        author = gallery.select("a[href*=/circle/]").eachText().joinToString()
            .ifEmpty { gallery.select("[itemprop=author]").text() }
        artist = gallery.select("[itemprop=author]").eachText().joinToString()
        genre = gallery.select("a[href*=/content/]").eachText().joinToString()
        description = gallery.select(".table-info tr")
            .mapNotNull { tr ->
                val cells = tr.select("td")
                val hidden = cells.any { cell ->
                    cell.text().lowercase().let { "content" in it || "ratings" in it }
                }
                if (cells.isEmpty() || hidden) return@mapNotNull null

                val values = cells.select("a").toList().ifEmpty { cells.drop(1) }
                cells[0].text() + ": " + values.joinToString { it.text() }
            }
            .joinToString("\n")
        status = SManga.COMPLETED
        thumbnail_url = gallery.select("img").attr("abs:src")
    }

    private fun List<Pair<String, String>>.toValue(): String = "[${this.joinToString(",") { "{\"id\":${it.first},\"name\":\"${it.second}\"}" }}]"

    private fun parsePageRange(query: String, minPages: Int = 1, maxPages: Int = 9999): Pair<Int, Int> {
        val num = query.filter(Char::isDigit).toIntOrNull() ?: -1
        fun limitedNum(number: Int = num): Int = number.coerceIn(minPages, maxPages)

        if (num < 0) return minPages to maxPages
        return when (query.firstOrNull()) {
            '<' -> 1 to if (query[1] == '=') limitedNum() else limitedNum(num - 1)

            '>' -> limitedNum(if (query[1] == '=') num else num + 1) to maxPages

            '=' -> when (query[1]) {
                '>' -> limitedNum() to maxPages
                '<' -> 1 to limitedNum()
                else -> limitedNum() to limitedNum()
            }

            else -> limitedNum() to limitedNum()
        }
    }

    private fun findTagByNameSubstring(tags: List<Tag>, substring: String): Pair<String, String>? {
        val tag = tags.find { it.name.contains(substring, ignoreCase = true) }
        return tag?.let { Pair(tag.id.toString(), tag.name) }
    }

    private suspend fun tagSearch(tag: String, type: String): Pair<String, String>? {
        val requestBody = FormBody.Builder()
            .add("text", tag)
            .build()

        val tags = client.post("$baseUrl/api/get/tags/search", Fetch.XHR, requestBody).parseAs<List<Tag>>()
        return findTagByNameSubstring(tags, type)
    }

    // Used by Komikku's delegated Pururin, which requests the gallery page itself and parses its details
    // with the site's old markup

    fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers).newBuilder()
        .fetch(Fetch.DOCUMENT)
        .tag(LegacyMarkup::class.java, LegacyMarkup)
        .build()

    // Komikku's default sends it without browser headers; the old markup doesn't change the chapter list
    fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    private object LegacyMarkup

    private fun Document.toLegacyMarkup(galleryPath: String) {
        select(".box-gallery .table-info").addClass("table-gallery-info")
        selectFirst(".box-gallery .cover-wrapper")?.let { cover ->
            // Komikku adds the scheme itself
            cover.appendElement("v-lazy-image").attr("src", cover.select("img").attr("abs:src").substringAfter(':'))
        }
        // Komikku reads the gallery id and slug from the link around the last [itemprop=name]
        body().appendElement("a").attr("href", galleryPath).appendElement("span").attr("itemprop", "name")
    }

    fun chapterListParse(response: Response): List<SChapter> {
        val galleryUrl = response.request.url.encodedPath
        return response.asJsoup().parseChapters().ifEmpty {
            listOf(
                SChapter.create().apply {
                    url = galleryUrl
                    name = "Chapter"
                },
            )
        }
    }
}
