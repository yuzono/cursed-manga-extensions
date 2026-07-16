package eu.kanade.tachiyomi.extension.all.pururin

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

@Source
class Pururin(
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    override val id: Long,
) : HttpSource() {

    private val searchLang: Pair<String, String>? by lazy {
        when (lang) {
            "en" -> Pair("13010", "english")
            "ja" -> Pair("13011", "japanese")
            else -> null
        }
    }
    private val langPath: String by lazy {
        when (lang) {
            "en" -> "/tags/language/13010/english"
            "ja" -> "/tags/language/13011/japanese"
            else -> ""
        }
    }

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/browse$langPath?sort=most-popular&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()

        val mangas = doc.select("a.card").map { element ->
            SManga.create().apply {
                title = element.attr("title")
                setUrlWithoutDomain(element.attr("abs:href"))
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }
        val hasNextPage = doc.selectFirst(".page-item [rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse$langPath?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search

    private fun List<Pair<String, String>>.toValue(): String = "[${this.joinToString(",") { "{\"id\":${it.first},\"name\":\"${it.second}\"}" }}]"

    private fun parsePageRange(query: String, minPages: Int = 1, maxPages: Int = 9999): Pair<Int, Int> {
        val num = query.filter(Char::isDigit).toIntOrNull() ?: -1
        fun limitedNum(number: Int = num): Int = number.coerceIn(minPages, maxPages)

        if (num < 0) return minPages to maxPages
        return when (query.firstOrNull()) {
            '<' -> 1 to if (query[1] == '=') limitedNum() else limitedNum(num + 1)

            '>' -> limitedNum(if (query[1] == '=') num else num + 1) to maxPages

            '=' -> when (query[1]) {
                '>' -> limitedNum() to maxPages
                '<' -> 1 to limitedNum(maxPages)
                else -> limitedNum() to limitedNum()
            }

            else -> limitedNum() to limitedNum()
        }
    }

    @Serializable
    class Tag(
        val id: Int,
        val name: String,
    )

    private fun findTagByNameSubstring(tags: List<Tag>, substring: String): Pair<String, String>? {
        val tag = tags.find { it.name.contains(substring, ignoreCase = true) }
        return tag?.let { Pair(tag.id.toString(), tag.name) }
    }

    private fun tagSearch(tag: String, type: String): Pair<String, String>? {
        val requestBody = FormBody.Builder()
            .add("text", tag)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/api/get/tags/search")
            .headers(headers)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        return findTagByNameSubstring(response.parseAs<List<Tag>>(), type)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val includeTags = mutableListOf<Pair<String, String>>()
        val excludeTags = mutableListOf<Pair<String, String>>()
        var pagesMin = 1
        var pagesMax = 9999
        var sortBy = "newest"

        searchLang?.let { includeTags.add(it) }

        filters.forEach {
            when (it) {
                is SelectFilter -> sortBy = it.getValue()

                is TypeFilter -> {
                    val (_, inactiveFilters) = it.state.partition { stIt -> stIt.state }
                    excludeTags += inactiveFilters.map { fil -> Pair(fil.value, "${fil.name} [Category]") }
                }

                is PageFilter -> {
                    if (it.state.isNotEmpty()) {
                        val (min, max) = parsePageRange(it.state)
                        pagesMin = min
                        pagesMax = max
                    }
                }

                is TextFilter -> {
                    if (it.state.isNotEmpty()) {
                        it.state.split(",").filter(String::isNotBlank).forEach { tag ->
                            val trimmed = tag.trim()
                            if (trimmed.startsWith('-')) {
                                tagSearch(trimmed.lowercase().removePrefix("-"), it.type)?.let { tagInfo ->
                                    excludeTags.add(tagInfo)
                                }
                            } else {
                                tagSearch(trimmed.lowercase(), it.type)?.let { tagInfo ->
                                    includeTags.add(tagInfo)
                                }
                            }
                        }
                    }
                }

                else -> {}
            }
        }

        // Searching with just one tag usually gives wrong results
        if (query.isEmpty()) {
            when {
                excludeTags.size == 1 && includeTags.isEmpty() -> excludeTags.addAll(excludeTags)

                includeTags.size == 1 && excludeTags.isEmpty() -> {
                    val url = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("browse")
                        addPathSegment("tags")
                        addPathSegment("content")
                        addPathSegment(includeTags[0].first)
                        addQueryParameter("sort", sortBy)
                        addQueryParameter("start_page", pagesMin.toString())
                        addQueryParameter("last_page", pagesMax.toString())
                        if (page > 1) addQueryParameter("page", page.toString())
                    }.build()
                    return GET(url, headers)
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

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            document.select(".box-gallery").let { e ->
                initialized = true
                title = e.select(".title").text()
                author = e.select("a[href*=/circle/]").eachText().joinToString().ifEmpty { e.select("[itemprop=author]").text() }
                artist = e.select("[itemprop=author]").eachText().joinToString()
                genre = e.select("a[href*=/content/]").eachText().joinToString()
                description = e.select(".box-gallery .table-info tr")
                    .filter { tr ->
                        tr.select("td").let { td ->
                            td.isNotEmpty() &&
                                td.none {
                                    it.text().contains("content", ignoreCase = true) || it.text().contains("ratings", ignoreCase = true)
                                }
                        }
                    }
                    .joinToString("\n") { tr ->
                        tr.select("td").let { td ->
                            var a = td.select("a").toList()
                            if (a.isEmpty()) a = td.drop(1)
                            td.first()!!.text() + ": " + a.joinToString { it.text() }
                        }
                    }
                status = SManga.COMPLETED
                thumbnail_url = e.select("img").attr("abs:src")
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup()
        .select(".table-collection tbody tr a")
        .map { element ->
            SChapter.create().apply {
                name = element.text()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        .reversed()
        .let { list ->
            list.ifEmpty {
                listOf(
                    SChapter.create().apply {
                        setUrlWithoutDomain(response.request.url.toString())
                        name = "Chapter"
                    },
                )
            }
        }

    // Pages

    override fun pageListParse(response: Response): List<Page> = response.asJsoup()
        .select(".gallery-preview a img")
        .mapIndexed { i, img ->
            Page(i, "", (if (img.hasAttr("abs:src")) img.attr("abs:src") else img.attr("abs:data-src")).replace("t.", "."))
        }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())
    override fun getFilterList() = getFilters()
}
