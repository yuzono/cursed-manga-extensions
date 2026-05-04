package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTags
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(4)
            // TachiyomiSY 1.12.0 still expects the legacy `window._gallery = JSON.parse(...)` payload
            // in its delegated NHentai metadata parser, even though the site now returns API JSON.
            .addInterceptor(::galleryDetailsCompatInterceptor)
            .addNetworkInterceptor(::authorizationInterceptor)
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
    var accessToken: String = ""

    fun authorizationInterceptor(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (!apiKey.isNullOrBlank()) {
            request = request.newBuilder().addHeader("Authorization", "Key $apiKey").build()
            val response = chain.proceed(request)
            if (response.code == 401) {
                response.close()
                throw IOException("Invalid API key")
            }
            return response
        } else if (request.url.toString().contains("/favorites")) {
            val newToken = cookieToken
            if (accessToken != newToken) accessToken = newToken
            if (accessToken.isNotBlank()) {
                request = request.newBuilder().addHeader("Authorization", "User $accessToken").build()
            }
            val response = chain.proceed(request)
            if (response.code == 401) {
                response.close()
                accessToken = ""
                throw IOException("Log in via WebView or add API key in the settings to view favorites")
            }
            return response
        }

        val response = chain.proceed(request)
        return response
    }

    private fun galleryDetailsCompatInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requiresCompatBody = request.header(DETAILS_COMPAT_HEADER) == "1"
        if (!requiresCompatBody) {
            return chain.proceed(request)
        }

        val cleanRequest = request.newBuilder()
            .removeHeader(DETAILS_COMPAT_HEADER)
            .build()

        return chain.proceed(cleanRequest).use { apiResponse ->
            val contentType = apiResponse.body.contentType()
            val apiBody = apiResponse.body.string()
            val responseBody = if (apiResponse.isSuccessful) {
                buildCompatGalleryBody(apiBody)
            } else {
                apiBody
            }
            apiResponse.newBuilder()
                .request(cleanRequest)
                .body(responseBody.toResponseBody(contentType))
                .build()
        }
    }

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
    private val galleryJsonRegex = Regex(""".parse\("(.*?)"\);""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()
    private fun String?.nullIfBlank() = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun fallbackTitle(id: Int) = "Gallery #$id"
    private fun buildCompatGalleryBody(apiBody: String): String {
        val data = apiBody.parseAs<Hentai>(json)
        val compatPayload = CompatGalleryResponse(
            id = data.id,
            mediaId = data.mediaId,
            title = data.title,
            images = CompatImages(
                pages = data.pages.map { page ->
                    CompatImage(
                        type = pathToLegacyType(page.path),
                        width = page.width,
                        height = page.height,
                    )
                },
                cover = CompatImage(
                    type = pathToLegacyType(data.cover?.path),
                    width = data.cover?.width,
                    height = data.cover?.height,
                ),
                thumbnail = CompatImage(
                    type = pathToLegacyType(data.thumbnail?.path),
                    width = data.thumbnail?.width,
                    height = data.thumbnail?.height,
                ),
            ),
            scanlator = data.scanlator.nullIfBlank(),
            uploadDate = data.toSChapter().date_upload / 1000,
            tags = data.tags,
            numPages = data.numPages.takeIf { it > 0 } ?: data.pages.size,
            numFavorites = data.numFavorites,
        )
        val compatJson = json.encodeToString(compatPayload)
        val escapedBody = compatJson
            .replace("\\", "\\u005c")
            .replace("\"", "\\u0022")
            .replace("\n", "\\u000a")
            .replace("\r", "\\u000d")
            .replace("\t", "\\u0009")
        return """window._gallery = JSON.parse("$escapedBody");"""
    }
    private fun String.decodeHtmlGalleryJson(): String {
        val galleryJson = galleryJsonRegex.find(this)?.groupValues?.getOrNull(1)
            ?: throw IOException("Unable to locate embedded gallery JSON")
        return galleryJson.replace(unicodeEscapeRegex) {
            it.groupValues[1].toInt(radix = 16).toChar().toString()
        }
    }
    private fun pathToLegacyType(path: String?): String? = when (path?.substringAfterLast('.', "")) {
        "webp" -> "w"
        "png" -> "p"
        "jpg", "jpeg" -> "j"
        "gif" -> "g"
        else -> null
    }
    private fun legacyThumbUrl(mediaId: String?, image: Image?, fileName: String): String? {
        val extension = when (image?.type) {
            "w" -> "webp"
            "p" -> "png"
            "j" -> "jpg"
            "g" -> "gif"
            else -> null
        }
        return if (mediaId != null && extension != null) {
            "$thumbServer/galleries/$mediaId/$fileName.$extension"
        } else {
            null
        }
    }
    private data class ParsedHentai(
        val id: Int,
        val title: Title,
        val tags: List<Tag>,
        val numPages: Int,
        val numFavorites: Long,
        val thumbnailPath: String? = null,
        val coverPath: String? = null,
    )
    private fun parseGalleryBody(body: String): ParsedHentai {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("{")) {
            val data = trimmed.parseAs<Hentai>(json)
            ParsedHentai(
                id = data.id,
                title = data.title,
                tags = data.tags,
                numPages = data.numPages.takeIf { it > 0 } ?: data.pages.size,
                numFavorites = data.numFavorites,
                thumbnailPath = data.thumbnail?.path,
                coverPath = data.cover?.path,
            )
        } else {
            val data = body.decodeHtmlGalleryJson().parseAs<LegacyHentai>(json)
            ParsedHentai(
                id = data.id,
                title = data.title,
                tags = data.tags,
                numPages = data.images.pages.size,
                numFavorites = data.numFavorites,
                thumbnailPath = legacyThumbUrl(data.mediaId, data.images.thumbnail, "thumb"),
                coverPath = legacyThumbUrl(data.mediaId, data.images.cover, "cover"),
            )
        }
    }

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
        val englishTitle = data.englishTitle.nullIfBlank()
        val japaneseTitle = data.japaneseTitle.nullIfBlank()
        val fullTitle = englishTitle ?: japaneseTitle ?: fallbackTitle(data.id)
        val shortTitle = englishTitle?.shortenTitle()
            ?: japaneseTitle?.shortenTitle()
            ?: fullTitle

        url = "/g/${data.id}/"
        title = if (displayFullTitle) fullTitle else shortTitle
        thumbnail_url = data.thumbnail?.let { "$thumbServer/$it" }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // Manga

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.removeSurrounding("/g/", "/")
        val requestUrl = "$apiUrl/galleries/$id"
        return GET(
            requestUrl,
            headersBuilder()
                .add(DETAILS_COMPAT_HEADER, "1")
                .build(),
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.use { parseGalleryBody(it.body.string()) }
        val englishTitle = data.title.english.nullIfBlank()
        val japaneseTitle = data.title.japanese.nullIfBlank()
        val prettyTitle = data.title.pretty.nullIfBlank()
        val fullTitle = englishTitle ?: japaneseTitle ?: prettyTitle ?: fallbackTitle(data.id)
        val shortTitle = prettyTitle
            ?: englishTitle?.shortenTitle()
            ?: japaneseTitle?.shortenTitle()
            ?: fullTitle
        val tagData = Hentai(
            id = data.id,
            tags = data.tags,
            title = data.title,
        )
        val artists = getArtists(tagData)
        val groups = getGroups(tagData)
        val thumbnailPath = data.thumbnailPath ?: data.coverPath

        return SManga.create().apply {
            url = "/g/${data.id}/"
            title = if (displayFullTitle) fullTitle else shortTitle
            thumbnail_url = thumbnailPath?.let {
                if (it.startsWith("http")) it else "$thumbServer/$it"
            }
            status = SManga.COMPLETED
            artist = artists
            author = groups ?: artists
            description = buildString {
                append("Full English and Japanese titles:\n")
                appendLine(fullTitle)
                japaneseTitle
                    ?.takeUnless { it == fullTitle }
                    ?.let(::appendLine)
                appendLine()
                append("Pages: ${data.numPages}\n")
                append("Favorited by: ${data.numFavorites}\n")
                append(getTagDescription(tagData))
            }
            genre = getTags(tagData)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
    }

    // Chapter List

    override fun chapterListRequest(manga: SManga): Request = searchMangaByIdRequest(manga.url.removeSurrounding("/g/", "/"))

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
        return data.pages
            .mapNotNull { it.path }
            .mapIndexed { i, path ->
                Page(i, imageUrl = "$imageServer/$path")
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
        SortFilter(SORT_OPTIONS.indexOfFirst { it.second == preferences.getString(SORT_PREF, "popular") }.coerceAtLeast(0)),
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
        private const val DETAILS_COMPAT_HEADER = "X-NHentai-Compat-Details"
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
