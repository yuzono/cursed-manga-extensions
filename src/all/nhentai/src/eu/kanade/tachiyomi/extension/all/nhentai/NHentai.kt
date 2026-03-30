package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTags
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : HttpSource(),
    ConfigurableSource {

    final override val baseUrl = "https://nhentai.net"
    val apiUrl = "$baseUrl/api/v2"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val name = "NHentai"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }
    var accessToken: String = ""

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(4)
            .addNetworkInterceptor(::authorizationInterceptor)
            .build()
    }

    fun authorizationInterceptor(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.url.toString().contains("/favorites")) {
            if (accessToken.isBlank()) {
                val cookies = webViewCookieManager.getCookie(baseUrl)
                if (cookies != null && cookies.isNotEmpty()) {
                    val cookieHeaders = cookies.split("; ").toList()
                    val tokenCookie = cookieHeaders.firstOrNull { it.startsWith("access_token=") }
                    if (tokenCookie != null) {
                        accessToken = tokenCookie.replace("access_token=", "")
                    }
                }
            }
            request = request.newBuilder().addHeader("Authorization", "User $accessToken").build()
        }
        val response = chain.proceed(request)
        if (response.code == 401) {
            accessToken = ""
            response.close()
            throw IOException("Log in via WebView to view favorites")
        }
        return response
    }

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent(
            filterInclude = listOf("chrome"),
        )

    val nhConfig: NHConfig by lazy {
        try {
            client.newCall(GET("$apiUrl/config", headers)).execute().body.string().parseAs<NHConfig>()
        } catch (_: IOException) {
            NHConfig(
                (1..4).map { n -> "https://i$n.nhentai.net" }.toList(),
                (1..4).map { n -> "https://t$n.nhentai.net" }.toList(),
            )
        }
    }
    val imageServer
        get() = nhConfig.image_servers.random()

    val thumbServer
        get() = nhConfig.thumb_servers.random()

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = SORT_PREF
            title = SORT_PREF
            entries = SORT_OPTIONS.map { it.first }.toTypedArray()
            entryValues = SORT_OPTIONS.map { it.second }.toTypedArray()
            summary = "%s"
            setDefaultValue("popular")
        }.also(screen::addPreference)

        screen.addRandomUAPreference()
    }

    override fun latestUpdatesRequest(page: Int) = GET(if (nhLang.isBlank()) "$apiUrl/galleries?page=$page" else "$apiUrl/search?query=language%3A$nhLang&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val res = response.parseAs<ResultNHentai>()
        val mangas = res.result.map { parseSearchData(it) }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage =
            (res.num_pages != null && res.num_pages > page) || (res.num_pages == null && res.total != null && res.total < page * res.per_page)
        return MangasPage(mangas, hasNextPage)
    }

    override fun popularMangaRequest(page: Int) = GET(
        if (nhLang.isBlank()) "$apiUrl/search/?query=\"\"&sort=popular&page=$page" else "$apiUrl/search?sort=popular&query=language%3A$nhLang&page=$page",
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val res = response.parseAs<ResultNHentai>()
        val mangas = res.result.map { parseSearchData(it) }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage =
            (res.num_pages != null && res.num_pages > page) || (res.num_pages == null && res.total != null && res.total < page * res.per_page)
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            searchMangaByIdParse(client.newCall(searchMangaByIdRequest(id)).execute(), id)
        }

        query.toIntOrNull() != null -> {
            searchMangaByIdParse(client.newCall(searchMangaByIdRequest(query)).execute(), query)
        }

        else -> super.getSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.findInstance<FavoriteFilter>()

        if (favoriteFilter?.state == true) {
            val url = "$apiUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery")
                .addQueryParameter("page", page.toString())

            return GET(url.build(), headers)
        } else {
            val url = "$apiUrl/search".toHttpUrl().newBuilder()
                // Blank query (Multi + sort by popular month/week/day) shows a 404 page
                // Searching for `""` is a hacky way to return everything without any filtering
                .addQueryParameter("query", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
                .addQueryParameter("page", page.toString())

            filterList.findInstance<SortFilter>()?.let { f ->
                url.addQueryParameter("sort", f.toUriPart())
            }

            return GET(url.build(), headers)
        }
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",").map(String::trim).filterNot(String::isBlank).forEach { tag ->
                val y = !(filter.name == "Pages" || filter.name == "Uploaded")
                if (tag.startsWith("-")) append("-")
                append(filter.name, ':')
                if (y) append('"')
                append(tag.removePrefix("-"))
                if (y) append('"')
                append(" ")
            }
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$apiUrl/galleries/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val res = response.parseAs<ResultNHentai>()
        val mangas = res.result.map { parseSearchData(it) }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage =
            (res.num_pages != null && res.num_pages > page) || (res.num_pages == null && res.total != null && res.total < page * res.per_page)
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = searchMangaByIdRequest(manga.url.removeSurrounding("/g/", "/"))

    override fun mangaDetailsParse(response: Response): SManga = parseData(response.parseAs<Hentai>())

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/galleries/${manga.url.split("/")[2]}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<Hentai>()
        return listOf(
            SChapter.create().apply {
                url = "/g/${data.id}/"
                name = "Chapter"
                scanlator = getGroups(data)
                date_upload = data.upload_date * 1000
            },
        )
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.removeSurrounding("/g/", "/")
        return GET("$apiUrl/galleries/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<Hentai>(json)
        return data.pages.mapIndexed { i, page ->
            Page(i, imageUrl = "$imageServer/${page.path}")
        }
    }

    fun parseSearchData(data: SearchHentai): SManga {
        val cdnUrl = nhConfig.thumb_servers.random()
        return SManga.create().apply {
            url = "/g/${data.id}/"
            title = if (displayFullTitle) {
                data.english_title ?: data.japanese_title!!
            } else {
                (data.english_title ?: data.japanese_title)!!.shortenTitle()
            }
            thumbnail_url = "$cdnUrl/${data.thumbnail}"
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    fun parseData(data: Hentai): SManga {
        val cdnUrl = nhConfig.thumb_servers.random()
        return SManga.create().apply {
            url = "/g/${data.id}/"
            title = if (displayFullTitle) {
                data.title.english ?: data.title.japanese ?: data.title.pretty!!
            } else {
                data.title.pretty ?: (data.title.english ?: data.title.japanese)!!.shortenTitle()
            }
            thumbnail_url = "$cdnUrl/${data.thumbnail.path}"
            status = SManga.COMPLETED
            artist = getArtists(data)
            author = getGroups(data) ?: getArtists(data)
            // Some people want these additional details in description
            description =
                "Full English and Japanese titles:\n".plus("${data.title.english ?: data.title.japanese ?: data.title.pretty ?: ""}\n")
                    .plus(data.title.japanese ?: "").plus("\n\n").plus("Pages: ${data.pages.size}\n")
                    .plus("Favorited by: ${data.num_favorites}\n").plus(getTagDescription(data))
            genre = getTags(data)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        Filter.Header("Add quote (\"...\") for exact match"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),

        Filter.Separator(),
        SortFilter(SORT_OPTIONS.indexOfFirst { it.second == preferences.getString(SORT_PREF, "popular") }),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("Tags")
    class CategoryFilter : AdvSearchEntryFilter("Categories")
    class GroupFilter : AdvSearchEntryFilter("Groups")
    class ArtistFilter : AdvSearchEntryFilter("Artists")
    class ParodyFilter : AdvSearchEntryFilter("Parodies")
    class CharactersFilter : AdvSearchEntryFilter("Characters")
    class UploadedFilter : AdvSearchEntryFilter("Uploaded")
    class PagesFilter : AdvSearchEntryFilter("Pages")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    private class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

    private class SortFilter(default: Int) :
        UriPartFilter(
            "Sort By",
            SORT_OPTIONS,
            default,
        )

    private inline fun <reified T> String.parseAs(): T {
        val data = Regex("""\\u([0-9A-Fa-f]{4})""").replace(this) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return json.decodeFromString(
            data,
        )
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>, state: Int) :
        Filter.Select<String>(
            displayName,
            vals.map { it.first }.toTypedArray(),
            state,
        ) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"

        private val SORT_OPTIONS = arrayOf(
            Pair("Popular: All Time", "popular"),
            Pair("Popular: Month", "popular-month"),
            Pair("Popular: Week", "popular-week"),
            Pair("Popular: Today", "popular-today"),
            Pair("Recent", "date"),
        )

        private const val SORT_PREF = "Default sort preference when searching"
    }
}
