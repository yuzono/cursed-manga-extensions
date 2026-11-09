package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.api.get
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

// Concrete so the generated sources keep this class name, which Komikku/SY match their delegated nHentai on
@Source
class NHentai(
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    override val id: Long,
) : KeiSource(),
    ConfigurableSource {

    private val nhLang: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }

    val apiUrl get() = "$baseUrl/api/v2"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val configMutex = Mutex()

    @Volatile
    private var nhConfig: NHConfig? = null

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    val apiKey
        get() = preferences.getString(API_KEY, "")

    val cookieToken
        get() = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .firstOrNull { it.name == "access_token" }
            ?.value.orEmpty()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addNetworkInterceptor(NhGalleryCacheInterceptor())
        addNetworkInterceptor(NhAuthorizationInterceptor())
        // 4 req/s with api key, 1/s otherwise
        rateLimit(1) { it.host == baseUrl.toHttpUrl().host && apiKey.isNullOrBlank() }
        rateLimit(4) { it.host == baseUrl.toHttpUrl().host }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", if (nhLang.isEmpty()) "\"\"" else "language:$nhLang")
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
        return client.get(url.build(), Fetch.XHR).toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (nhLang.isEmpty()) {
            "$apiUrl/galleries".toHttpUrl().newBuilder()
        } else {
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", "language:$nhLang")
        }
        url.addQueryParameter("page", page.toString())
        return client.get(url.build(), Fetch.XHR).toMangasPage()
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

        val nhLangSearch = if (nhLang.isEmpty()) "" else "language:$nhLang "
        val advQuery = filters.combineQuery()
        val favoriteFilter = filters.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage =
            filters.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

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
                    filters.firstInstanceOrNull<SortFilter>()?.let { addQueryParameter("sort", it.toUriPart()) }
                }
        }
        return client.get(url.build(), Fetch.XHR).toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "g") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return getGalleryManga(id)
    }

    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val id = manga.url.removeSurrounding("/g/", "/")
        val thumbServers = getConfig().thumbServers
        return client.get("$apiUrl/galleries/$id/related", Fetch.XHR).parseAs<PaginatedResponse<GalleryItem>>()
            .result.mapNotNull { it.toSMangaOrNull(thumbServers.random(), displayFullTitle) }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.removeSurrounding("/g/", "/")
        val data = client.get("$apiUrl/galleries/$id", Fetch.XHR).parseAs<Hentai>()
        return SMangaUpdate(
            manga = if (fetchDetails) data.toSManga(getConfig().thumbServers.random(), displayFullTitle) else manga,
            chapters = listOf(data.toSChapter()),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val data = getGallery(chapter.url.removeSurrounding("/g/", "/"))
        val imageServers = getConfig().imageServers
        return data.pages.mapIndexed { i, page ->
            Page(i, imageUrl = "${imageServers.random()}/${page.path}")
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(preferences.getString(SORT_PREF, "popular"))

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
            summary = "Profile > Settings > API Keys. With a key, up to 4 requests per second are made instead of 1"
            setDefaultValue("")
        }.let(screen::addPreference)
    }

    // Requested once; the default servers are kept if it fails
    private suspend fun getConfig(): NHConfig = nhConfig ?: configMutex.withLock {
        nhConfig ?: try {
            client.get("$apiUrl/config", Fetch.XHR).parseAs<NHConfig>()
                .also { require(it.imageServers.isNotEmpty() && it.thumbServers.isNotEmpty()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NHConfig((1..4).map { "https://i$it.nhentai.net" }, (1..4).map { "https://t$it.nhentai.net" })
        }.also { nhConfig = it }
    }

    // Lookups bypass getMangaUpdate, so mark the result resolved to avoid a second gallery fetch
    private suspend fun getGalleryManga(id: String): SManga = getGallery(id)
        .toSManga(getConfig().thumbServers.random(), displayFullTitle)
        .apply { initialized = true }

    private suspend fun getGallery(id: String): Hentai = client.get("$apiUrl/galleries/$id", Fetch.XHR).parseAs()

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

    private inner class NhAuthorizationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val url = request.url

            if (url.host != baseUrl.toHttpUrl().host || !API_PATH_REGEX.matches(url.encodedPath)) {
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
        private const val GALLERY_CACHE_MAX_AGE_SECONDS = 7200
        private const val TITLE_PREF = "Display manga title as:"

        private const val SORT_PREF = "Default sort preference when searching"
    }

    // Used by Komikku's delegated nHentai, which parses the details response into its metadata itself

    fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/galleries/${manga.url.removeSurrounding("/g/", "/")}", headers)
        .newBuilder().fetch(Fetch.XHR).build()

    fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    fun chapterListParse(response: Response): List<SChapter> = listOf(response.parseAs<Hentai>().toSChapter())
}
