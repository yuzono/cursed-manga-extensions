package eu.kanade.tachiyomi.extension.all.nhentai

import android.app.Application
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NHentai :
    KeiSource(),
    ConfigurableSource {

    private val nhLang: String by lazy {
        when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }
    }

    val apiUrl get() = "$baseUrl/api/v2"

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }

    private val galleryCacheControl = CacheControl.Builder().maxStale(2, TimeUnit.HOURS).build()

    @Volatile
    private var nhConfig: NHConfig? = null

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    val apiKey
        get() = preferences.getString(API_KEY, "")

    val cookieToken
        get() = webViewCookieManager.getCookie(baseUrl)
            ?.split("; ")
            ?.firstOrNull { it.startsWith("access_token=") }
            ?.removePrefix("access_token=") ?: ""

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        val (permits, period) = preferences.parseRateLimit()

        val app = Injekt.get<Application>()
        val cacheParent = app.cacheDir
            .takeIf { it.exists() || it.mkdirs() }
            ?: app.externalCacheDir
            ?: app.filesDir
        val cacheDirectory = File(cacheParent, "nhentai_api_cache_$lang")

        return cache(Cache(directory = cacheDirectory, maxSize = 5L * 1024 * 1024))
            .addInterceptor(NhApiRetryInterceptor())
            .addNetworkInterceptor(NhGalleryCacheInterceptor())
            .addNetworkInterceptor(NhAuthorizationInterceptor())
            .rateLimit(permits, period.seconds) { it.host == baseUrlHost }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = setRandomUserAgent(
        filterInclude = listOf("chrome"),
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", if (nhLang.isEmpty()) "\"\"" else "language:$nhLang")
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
        return client.get(url.build()).toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (nhLang.isEmpty()) {
            "$apiUrl/galleries".toHttpUrl().newBuilder()
        } else {
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", "language:$nhLang")
        }
        url.addQueryParameter("page", page.toString())
        return client.get(url.build()).toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val galleryId = when {
            query.startsWith(PREFIX_ID_SEARCH) -> query.removePrefix(PREFIX_ID_SEARCH)
            query.toIntOrNull() != null -> query
            else -> null
        }
        if (galleryId != null) {
            return MangasPage(listOf(getGalleryManga(galleryId)), false)
        }

        val filterList = if (filters.isEmpty()) getFilterList(null) else filters
        val nhLangSearch = if (nhLang.isEmpty()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage =
            filterList.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        val url = if (favoriteFilter?.state == true) {
            "$apiUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery")
                .addQueryParameter("page", offsetPage.toString())
        } else {
            "$apiUrl/search".toHttpUrl().newBuilder()
                // Blank query (Multi + sort by popular month/week/day) shows a 404 page
                // Searching for `""` is a hacky way to return everything without any filtering
                .addQueryParameter("query", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
                .addQueryParameter("page", offsetPage.toString())
                .apply {
                    filterList.firstInstanceOrNull<SortFilter>()?.let { addQueryParameter("sort", it.toUriPart()) }
                }
        }
        return client.get(url.build()).toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrlHost || url.pathSegments.firstOrNull() != "g") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return getGalleryManga(id)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.removeSurrounding("/g/", "/")
        val data = client.get("$apiUrl/galleries/$id", cacheControl = galleryCacheControl).parseAs<Hentai>()
        return SMangaUpdate(
            manga = data.toSManga(getConfig().thumbServers.random(), displayFullTitle),
            chapters = listOf(data.toSChapter()),
        )
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val data = getGallery(chapter.url.removeSurrounding("/g/", "/"))
        val imageServers = getConfig().imageServers
        return data.pages.mapIndexed { i, page ->
            Page(i, imageUrl = "${imageServers.random()}/${page.path}")
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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

    private suspend fun getConfig(): NHConfig {
        nhConfig?.let { return it }
        return try {
            client.get("$apiUrl/config").parseAs<NHConfig>().also {
                require(it.imageServers.isNotEmpty() && it.thumbServers.isNotEmpty())
                nhConfig = it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not cached, so a failed lookup can never replace a config stored by a concurrent call
            nhConfig ?: NHConfig(
                (1..4).map { n -> "https://i$n.nhentai.net" },
                (1..4).map { n -> "https://t$n.nhentai.net" },
            )
        }
    }

    // Lookups bypass getMangaUpdate, so mark the result resolved to avoid a second gallery fetch
    private suspend fun getGalleryManga(id: String): SManga = getGallery(id)
        .toSManga(getConfig().thumbServers.random(), displayFullTitle)
        .apply { initialized = true }

    private suspend fun getGallery(id: String): Hentai = client.get("$apiUrl/galleries/$id").parseAs()

    private suspend fun Response.toMangasPage(): MangasPage {
        val currentPage = request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val res = parseAs<PaginatedResponse<GalleryItem>>()
        val thumbServers = getConfig().thumbServers
        val mangas = res.result.mapNotNull { it.toSMangaOrNull(thumbServers.random(), displayFullTitle) }
        val hasNextPage =
            (res.numPages != null && res.numPages > currentPage) ||
                (res.numPages == null && res.total != null && res.total > currentPage * res.perPage)
        return MangasPage(mangas, hasNextPage)
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
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

    private inner class NhApiRetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url

            if (url.host != baseUrlHost || !API_PATH_REGEX.matches(url.encodedPath)) {
                return chain.proceed(request)
            }

            val response = chain.proceed(request)
            // The request returned normally or this is already a retry request and still got a 429
            if (response.code != 429 || request.header(BACKOFF_RETRY_HEADER) != null) {
                return response
            }

            // Do not block OkHttp threads; only immediate one-shot retry
            val retryAfter = response.header("Retry-After")
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

            if (url.host != baseUrlHost || !API_PATH_REGEX.matches(url.encodedPath)) {
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
                if (accessToken.isNotEmpty()) {
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

    companion object {
        const val API_KEY = "api_key"
        const val PREFIX_ID_SEARCH = "id:"
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
}
