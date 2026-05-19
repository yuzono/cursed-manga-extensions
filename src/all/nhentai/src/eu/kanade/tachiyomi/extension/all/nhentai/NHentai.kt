package eu.kanade.tachiyomi.extension.all.nhentai

import android.app.Application
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTags
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    override val client: OkHttpClient by lazy {
        val (permits, period) = preferences.parseRateLimit()

        val app = Injekt.get<Application>()
        val cacheParent = app.cacheDir
            .takeIf { it.exists() || it.mkdirs() }
            ?: app.externalCacheDir
            ?: app.filesDir
        val cacheDirectory = File(cacheParent, "nhentai_api_cache_$lang")

        network.cloudflareClient.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), permits, period, TimeUnit.SECONDS)
            .cache(
                Cache(
                    directory = cacheDirectory,
                    maxSize = 5L * 1024 * 1024, // 5 MiB which should be enough
                ),
            )
            .addInterceptor(NhApiRetryInterceptor())
            .addNetworkInterceptor(NhGalleryCacheInterceptor())
            .addNetworkInterceptor(NhAuthorizationInterceptor())
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent(
            filterInclude = listOf("chrome"),
        )

    // Authentication

    val apiKey
        get() = preferences.getString(API_KEY, "")
    val cookieToken
        get() = webViewCookieManager.getCookie(baseUrl)
            ?.split("; ")
            ?.firstOrNull { it.startsWith("access_token=") }
            ?.replace("access_token=", "") ?: ""

    // Cdns

    val nhConfig: NHConfig by lazy {
        try {
            client.newCall(GET("$apiUrl/config", headers)).execute().parseAs<NHConfig>(json)
        } catch (_: Exception) {
            NHConfig(
                (1..4).map { n -> "https://i$n.nhentai.net" }.toList(),
                (1..4).map { n -> "https://t$n.nhentai.net" }.toList(),
            )
        }
    }

    val imageServer
        get() = nhConfig.imageServers.random()

    val thumbServer
        get() = nhConfig.thumbServers.random()

    // Preferences

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

        EditTextPreference(screen.context).apply {
            key = API_KEY
            title = "API key"
            summary = "Profile > Settings > API Keys"
            setDefaultValue("")
        }.let(screen::addPreference)

        screen.addRandomUAPreference()

        ListPreference(screen.context).apply {
            key = RATE_LIMIT_PREF
            title = "Network Rate Limit"
            summary = "%s"
            entries = RATE_LIMIT_OPTIONS.map { it.first }.toTypedArray()
            entryValues = RATE_LIMIT_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(RATE_LIMIT_DEFAULT)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Restart app to apply", Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)
    }

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

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", if (nhLang.isBlank()) "\"\"" else "language:$nhLang")
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // Search

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(id)).await().use { searchMangaByIdParse(it) }
        }

        query.toIntOrNull() != null -> {
            client.newCall(searchMangaByIdRequest(query)).await().use { searchMangaByIdParse(it) }
        }

        else -> super.getSearchManga(page, query, filters)
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

    private fun searchMangaByIdParse(response: Response): MangasPage {
        val details = mangaDetailsParse(response)
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val res = response.parseAs<PaginatedResponse<GalleryItem>>(json)
        val mangas = res.result.mapNotNull { runCatching { parseSearchData(it) }.getOrNull() }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage =
            (res.numPages != null && res.numPages > page) || (res.numPages == null && res.total != null && res.total > page * res.perPage)
        return MangasPage(mangas, hasNextPage)
    }

    fun parseSearchData(data: GalleryItem): SManga = SManga.create().apply {
        url = "/g/${data.id}/"
        title = (data.englishTitle ?: data.japaneseTitle)!!.let {
            if (displayFullTitle) it else it.shortenTitle()
        }
        thumbnail_url = "$thumbServer/${data.thumbnail}"
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // Manga

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = searchMangaByIdRequest(manga.url.removeSurrounding("/g/", "/"))

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<Hentai>(json)

        return SManga.create().apply {
            url = "/g/${data.id}/"
            title = if (displayFullTitle) {
                data.title.english ?: data.title.japanese ?: data.title.pretty!!
            } else {
                data.title.pretty ?: (data.title.english ?: data.title.japanese)!!.shortenTitle()
            }
            thumbnail_url = "$thumbServer/${data.thumbnail.path}"
            status = SManga.COMPLETED
            artist = getArtists(data)
            author = getGroups(data) ?: getArtists(data)
            // Some people want these additional details in description
            description = "Full English and Japanese titles:\n"
                .plus("${data.title.english ?: data.title.japanese ?: data.title.pretty ?: ""}\n")
                .plus(data.title.japanese ?: "")
                .plus("\n\n")
                .plus("Pages: ${data.numPages}\n")
                .plus("Favorited by: ${data.numFavorites}\n")
                .plus(getTagDescription(data))
            genre = getTags(data)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
    }

    // Chapter List

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga).newBuilder()
        .cacheControl(CacheControl.Builder().maxStale(2, TimeUnit.HOURS).build())
        .build()

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<Hentai>(json)
        return listOf(data.toSChapter())
    }

    // Pages

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

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // Filters
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
        SortFilter(
            SORT_OPTIONS.indexOfFirst { it.second == preferences.getString(SORT_PREF, "popular") }
                .coerceAtLeast(0),
        ),
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

    private class SortFilter(default: Int) : UriPartFilter("Sort By", SORT_OPTIONS, default)

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        state: Int,
    ) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val API_KEY = "api_key"
        const val PREFIX_ID_SEARCH = "id:"
        private const val NHENTAI_HOST = "nhentai.net"
        private val GALLERY_PATH_REGEX = Regex("^/api/v2/galleries/\\d+/?$")
        private val API_PATH_REGEX = Regex("^/api/v2/.*$")
        private const val BACKOFF_RETRY_HEADER = "X-NHentai-Backoff-Retry"
        private const val GALLERY_CACHE_MAX_AGE_SECONDS = 7200
        private const val RATE_LIMIT_PREF = "rate_limit_pref"
        private const val RATE_LIMIT_DEFAULT = "1/1"
        private const val RATE_LIMIT_MIN_PERMITS = 1
        private const val RATE_LIMIT_MAX_PERMITS = 10
        private const val RATE_LIMIT_MIN_PERIOD_SECONDS = 1L
        private const val RATE_LIMIT_MAX_PERIOD_SECONDS = 60L
        private val RATE_LIMIT_OPTIONS = arrayOf(
            Pair("0.25 rps", "1/4"),
            Pair("0.5 rps", "1/2"),
            Pair("1 rps (default)", "1/1"),
            Pair("2 rps", "2/1"),
            Pair("4 rps (recommended with API key)", "4/1"),
        )
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

    private fun SharedPreferences.parseRateLimit(): Pair<Int, Long> {
        val raw = getString(RATE_LIMIT_PREF, RATE_LIMIT_DEFAULT).orEmpty()
        return parseRateLimitString(raw) ?: defaultRateLimit()
    }

    private fun defaultRateLimit(): Pair<Int, Long> = requireNotNull(parseRateLimitString(RATE_LIMIT_DEFAULT))

    private fun parseRateLimitString(raw: String): Pair<Int, Long>? {
        val parts = raw.split("/", limit = 2)
        if (parts.size != 2) return null

        val permits = parts[0].toIntOrNull()
        val period = parts[1].toLongOrNull()
        if (permits == null || period == null) return null

        return permits.coerceIn(RATE_LIMIT_MIN_PERMITS, RATE_LIMIT_MAX_PERMITS) to
            period.coerceIn(RATE_LIMIT_MIN_PERIOD_SECONDS, RATE_LIMIT_MAX_PERIOD_SECONDS)
    }

    private class NhGalleryCacheInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (!GALLERY_PATH_REGEX.matches(response.request.url.encodedPath)) return response

            // Tell HttpClient to cache the gallery API JSON response for 2 hours
            return response.newBuilder()
                .removeHeader("Cache-Control")
                .removeHeader("Expires")
                .removeHeader("Pragma")
                .header("Cache-Control", "max-age=$GALLERY_CACHE_MAX_AGE_SECONDS")
                .build()
        }
    }

    private class NhApiRetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url

            // If the request is not to the NHentai API, do not retry
            if (url.host != NHENTAI_HOST || !API_PATH_REGEX.matches(url.encodedPath)) {
                return chain.proceed(request)
            }

            val response = chain.proceed(request)
            // The request returned normally or this is already a retry request and still got a 429
            if (response.code != 429 || request.header(BACKOFF_RETRY_HEADER) != null) {
                return response
            }

            // Do not block OkHttp threads; only immediate one-shot retry
            val retryAfter = response.header("Retry-After")?.trim()
            // Server asks us to wait for a certain amount of time before retrying
            if (!retryAfter.isNullOrEmpty() && retryAfter.toLongOrNull() != 0L) return response

            response.close()
            val retryRequest = request.newBuilder()
                .header(BACKOFF_RETRY_HEADER, "1")
                .build()
            return chain.proceed(retryRequest)
        }
    }

    private inner class NhAuthorizationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val url = request.url

            // If the request is not to the NHentai API, do not add authorization headers
            if (url.host != NHENTAI_HOST || !API_PATH_REGEX.matches(url.encodedPath)) {
                return chain.proceed(request)
            }

            if (!apiKey.isNullOrBlank()) {
                request = request.newBuilder().addHeader("Authorization", "Key $apiKey").build()
                val response = chain.proceed(request)
                if (response.code == 401) {
                    response.close()
                    throw IOException("Invalid API key")
                }
                return response
            }

            if (url.encodedPath.contains("/favorites")) {
                val accessToken = cookieToken
                if (accessToken.isNotBlank()) {
                    request = request.newBuilder().addHeader("Authorization", "User $accessToken").build()
                }
                val response = chain.proceed(request)
                if (response.code == 401) {
                    response.close()
                    throw IOException("Log in via WebView or add API key in the settings to view favorites")
                }
                return response
            }

            return chain.proceed(request)
        }
    }
}
