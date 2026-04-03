package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : HttpSource(),
    ConfigurableSource {

    final override val baseUrl = "https://nhentai.net"

    private val apiUrl = "$baseUrl/api/v2"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val name = "NHentai"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }

    private var accessToken: String = ""

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(4)
            .addNetworkInterceptor(::authorizationInterceptor)
            .build()
    }

    private fun authorizationInterceptor(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.url.encodedPath.contains("/favorites")) {
            val newToken = webViewCookieManager.getCookie(baseUrl)
                ?.split("; ")
                ?.firstOrNull { it.startsWith("access_token=") }
                ?.removePrefix("access_token=")

            if (newToken != accessToken) {
                accessToken = newToken.orEmpty()
            }
            if (accessToken.isNotBlank()) {
                request = request.newBuilder().addHeader("Authorization", "User $accessToken").build()
            }
        }
        val response = chain.proceed(request)
        if (request.url.encodedPath.contains("/favorites") && response.code == 401) {
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

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun shortenTitle(text: String) = text.replace(shortenTitleRegex, "").trim()

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

        screen.addRandomUAPreference()
    }

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", if (nhLang.isBlank()) "\"\"" else "language:$nhLang")
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (nhLang.isBlank()) {
            "$apiUrl/galleries".toHttpUrl().newBuilder()
        } else {
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", "language:$nhLang")
        }
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // Search

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        }

        query.toIntOrNull() != null -> {
            client.newCall(searchMangaByIdRequest(query))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, query) }
        }

        else -> {
            val request = searchMangaRequest(page, query, filters)
            client.newCall(request)
                .asObservableSuccess()
                .map { response -> searchMangaParse(response) }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage =
            filterList.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val url = "$apiUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery")
                .addQueryParameter("page", offsetPage.toString())

            return GET(url.build(), headers)
        } else {
            val url = "$apiUrl/search".toHttpUrl().newBuilder()
                // Blank query (Multi + sort by popular month/week/day) shows a 404 page
                // Searching for `""` is a hacky way to return everything without any filtering
                .addQueryParameter("query", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
                .addQueryParameter("page", offsetPage.toString())

            filterList.firstInstanceOrNull<SortFilter>()?.let { f ->
                url.addQueryParameter("sort", f.toUriPart())
            }

            return GET(url.build(), headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<PaginatedResponse<GalleryListItem>>(json)
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val hasNextPage = result.numPages > page
        return MangasPage(result.result.map { it.toSManga(displayFullTitle, ::shortenTitle, getThumbServer()) }, hasNextPage)
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filterNot(String::isBlank)
                .forEach { tag ->
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
        val details = response.parseAs<Hentai>(json).toSManga(full = false, displayFullTitle, ::shortenTitle, getThumbServer())
        return MangasPage(listOf(details), false)
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.removeSurrounding("/g/", "/")
        return GET("$apiUrl/galleries/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<Hentai>(json)
        return data.toSManga(full = true, displayFullTitle, ::shortenTitle, getThumbServer())
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url.removeSurrounding("/g/", "/")}/"

    // Chapters

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<Hentai>(json)
        return listOf(data.toSChapter())
    }

    // Pages

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.removeSurrounding("/g/", "/")
        return GET("$apiUrl/galleries/$id", headers)
    }

    private val nhConfig: Config by lazy {
        try {
            client.newCall(GET("$apiUrl/config", headers)).execute().parseAs<Config>(json)
        } catch (_: IOException) {
            Config(
                (1..4).map { "https://i$it.nhentai.net" },
                (1..4).map { "https://t$it.nhentai.net" },
            )
        }
    }

    private fun getThumbServer(): String = nhConfig.thumbServers.random()
    private fun getImageServer(): String = nhConfig.imageServers.random()

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<Hentai>(json)
        val imageServer = getImageServer()

        return data.pages.mapIndexed { i, page ->
            Page(i, imageUrl = "$imageServer/${page.path}")
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // Helpers

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
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
        SortFilter(),
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

    private class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popular: All Time", "popular"),
                Pair("Popular: Month", "popular-month"),
                Pair("Popular: Week", "popular-week"),
                Pair("Popular: Today", "popular-today"),
                Pair("Recent", "date"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
    }
}
