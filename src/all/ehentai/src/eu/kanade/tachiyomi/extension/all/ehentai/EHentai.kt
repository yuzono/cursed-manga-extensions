package eu.kanade.tachiyomi.extension.all.ehentai

import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.CheckBox
import eu.kanade.tachiyomi.source.model.Filter.Select
import eu.kanade.tachiyomi.source.model.Filter.Text
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

@Source
abstract class EHentai :
    KeiSource(),
    ConfigurableSource {

    private val ehLang: String = when (lang) {
        "ja" -> "japanese"
        "en" -> "english"
        "zh" -> "chinese"
        "nl" -> "dutch"
        "fr" -> "french"
        "de" -> "german"
        "hu" -> "hungarian"
        "it" -> "italian"
        "ko" -> "korean"
        "pl" -> "polish"
        "pt-BR" -> "portuguese"
        "ru" -> "russian"
        "es" -> "spanish"
        "th" -> "thai"
        "vi" -> "vietnamese"
        "none" -> "n/a"
        "other" -> "other"
        else -> ""
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }
    private val memberId: String by lazy { getMemberIdPref() }
    private val passHash: String by lazy { getPassHashPref() }
    private val igneous: String by lazy { getIgneousPref() }
    private val forceEh: Boolean by lazy { getForceEhPref() }

    // The site itself changes with the login state: exhentai.org when member cookies are set, e-hentai.org otherwise.
    override val baseUrl: String
        get() = when {
            System.getenv("CI") == "true" -> "https://e-hentai.org"
            !forceEh && memberId.isNotEmpty() && passHash.isNotEmpty() -> "https://exhentai.org"
            else -> "https://e-hentai.org"
        }

    // Listings are paged with a "next" cursor (last gallery id of the previous page), not page numbers.
    // Cursors are kept per listing url and page, so interleaved listings and searches can't overwrite each other.
    // The cache is bounded, so a cursor can be evicted: loadListing rebuilds it instead of ending the listing.
    private class Cursor(val requestId: Long, val value: String)

    private val nextCursors = object : LinkedHashMap<String, Cursor>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cursor>): Boolean = size > MAX_CURSORS
    }

    // Ordered by when a listing request was sent, so a slow older response can't replace a newer cursor
    private val listingRequestCounter = AtomicLong()

    private class ListingResult(val page: MangasPage, val nextCursor: String?)

    private fun cursorKey(listingUrl: String, page: Int) = "$listingUrl#$page"

    private fun isLangNatural(): Boolean = lang !in listOf("none", "other")

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage = if (isLangNatural()) {
        loadListing("$baseUrl/?f_search=${languageTag()}&f_srdd=5&f_sr=on", page)
    } else {
        getLatestUpdates(page)
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage = loadListing(baseUrl, page)

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            return MangasPage(listOf(fetchGallery(query.removePrefix(PREFIX_ID_SEARCH))), false)
        }

        val enforceLanguageFilter = filters.firstInstanceOrNull<EnforceLanguageFilter>()?.state == true
        var modifiedQuery = when {
            !isLangNatural() -> query
            query.isBlank() -> languageTag(enforceLanguageFilter)
            else -> languageTag(enforceLanguageFilter).let { if (it.isNotEmpty()) "$query,$it" else query }
        }
        filters.filterIsInstance<TextFilter>().forEach { filter ->
            if (filter.state.isNotEmpty()) {
                val splitted = filter.state.split(",").filter(String::isNotBlank)
                splitted.forEach { tag ->
                    val trimmed = tag.trim().lowercase()
                    val tagName = trimmed.removePrefix("-")
                    val isExclude = trimmed.startsWith('-')
                    modifiedQuery += if (isExclude) {
                        " -${filter.type}:\"$tagName\""
                    } else {
                        " ${filter.type}:\"$tagName\""
                    }
                }
            }
        }
        val baseSearchUrl = "$baseUrl$QUERY_PREFIX&f_search=${URLEncoder.encode(modifiedQuery, "UTF-8")}"
        val uri = Uri.parse(baseSearchUrl).buildUpon()
        // The site returns nothing when no genre is selected, so select them all
        filters.firstInstanceOrNull<GenreGroup>()?.state?.let {
            if (it.none(GenreOption::state)) {
                for (i in it) {
                    i.state = true
                }
            }
        }

        filters.forEach {
            if (it is UriFilter) it.addToUri(uri)
        }

        val listingUrl = uri.toString()
        return loadListing(listingUrl, page, useFrom = listingUrl.contains("f_spf") || listingUrl.contains("f_spt"))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") !in setOf("e-hentai.org", "exhentai.org")) return null
        val segments = url.pathSegments
        if (segments.size < 3 || segments[0] != "g") return null
        return fetchGallery("${segments[1]}/${segments[2]}")
    }

    private suspend fun fetchGallery(idAndToken: String): SManga = client.get("$baseUrl/g/$idAndToken").toMangaDetails()

    private suspend fun loadListing(listingUrl: String, page: Int, useFrom: Boolean = false): MangasPage {
        // Resume from the nearest page whose cursor is still cached, or from the first page if none is
        var current = 1
        var cursor: String? = null
        for (candidate in page downTo 2) {
            val cached = synchronized(nextCursors) { nextCursors[cursorKey(listingUrl, candidate)] }
            if (cached != null) {
                current = candidate
                cursor = cached.value
                break
            }
        }

        while (true) {
            val result = fetchListingPage(listingUrl, current, cursor, useFrom)
            if (current == page) return result.page
            if (!result.page.hasNextPage) return MangasPage(emptyList(), false)
            cursor = result.nextCursor ?: return MangasPage(emptyList(), false)
            current++
        }
    }

    private suspend fun fetchListingPage(listingUrl: String, page: Int, cursor: String?, useFrom: Boolean): ListingResult {
        val requestId = listingRequestCounter.incrementAndGet()
        var url = listingUrl
        if (cursor != null) {
            if (useFrom) url = addParam(url, "from", cursor)
            url = addParam(url, "next", cursor)
        }
        return parseMangaPage(client.get(url), listingUrl, page, requestId)
    }

    private fun languageTag(enforceLanguageFilter: Boolean = false): String = if (enforceLanguageFilter || getEnforceLanguagePref()) "language:$ehLang" else ""

    private fun parseMangaPage(response: Response, listingUrl: String, page: Int, requestId: Long): ListingResult {
        val doc = response.asJsoup()
        val mangaElements = doc.select("table.itg td.glname")
            .let { elements ->
                if (isLangNatural() && getEnforceLanguagePref()) {
                    // elements without a language tag are kept
                    elements.filter { element ->
                        element.selectFirst("div[title^=language]")?.let { it.text() == ehLang } ?: true
                    }
                } else {
                    elements
                }
            }

        val mangas = mangaElements.mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val galleryTitle = link.selectFirst(".glink")?.text() ?: return@mapNotNull null
            SManga.create().apply {
                title = galleryTitle
                url = ExGalleryMetadata.normalizeUrl(link.attr("href"))
                thumbnail_url = element.parent()?.selectFirst(".glthumb img")?.let { img ->
                    img.absUrl("data-src").ifEmpty { img.absUrl("src") }.ifEmpty { null }
                }
            }
        }

        val nextId = doc.selectFirst("a#unext[href]")?.absUrl("href")?.toHttpUrlOrNull()?.queryParameter("next")
            ?: doc.select("table.itg td.glname").lastOrNull()?.selectFirst("a")
                ?.let { ExGalleryMetadata.galleryId(it.attr("href")) }
        nextId?.let { id ->
            val key = cursorKey(listingUrl, page + 1)
            synchronized(nextCursors) {
                val existing = nextCursors[key]
                if (existing == null || existing.requestId <= requestId) {
                    nextCursors[key] = Cursor(requestId, id)
                }
            }
        }

        val hasNextPage = doc.select("a#unext[href]").hasText()

        return ListingResult(MangasPage(mangas, hasNextPage), nextId)
    }

    // Details and chapters

    // A gallery is a single chapter built from the manga url, so there is no request behind the chapter list
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) client.get("$baseUrl${manga.url}").toMangaDetails(manga.url) else manga
        val updatedChapters = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Chapter"
                    chapter_number = 1f
                },
            )
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun Response.toMangaDetails(storedUrl: String? = null): SManga {
        val galleryPath = request.url.encodedPath
        val doc = asJsoup()
        return with(ExGalleryMetadata()) {
            // Same url form as the listing, so a gallery has one stored url however it was added.
            // Entries saved by the old id search have a tokenless /g/<id>/ path: keep it as stored when the site doesn't redirect to a tokenized one
            url = if (ExGalleryMetadata.hasToken(galleryPath)) {
                ExGalleryMetadata.normalizeUrl(galleryPath)
            } else {
                storedUrl ?: galleryPath
            }
            title = doc.select("#gn").text().nullIfBlank()

            altTitle = doc.select("#gj").text().nullIfBlank()

            // Thumbnail is set as background of element in style attribute
            thumbnailUrl = doc.select("#gd1 div").attr("style").nullIfBlank()?.let {
                it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
            }
            category = doc.select("#gdc div").text().nullIfBlank()?.lowercase()

            uploader = doc.select("#gdn").text().nullIfBlank()

            doc.select("#gdd tr").forEach {
                it.select(".gdt1")
                    .text()
                    .nullIfBlank()
                    ?.let { left ->
                        it.select(".gdt2")
                            .text()
                            .nullIfBlank()
                            ?.let { right ->
                                ignore {
                                    when (
                                        left.removeSuffix(":")
                                            .lowercase()
                                    ) {
                                        "posted" -> datePosted = EX_DATE_FORMAT.tryParseDateTime(right, ZoneOffset.UTC)

                                        "visible" -> visible = right.nullIfBlank()

                                        "language" -> {
                                            language = right.removeSuffix(TR_SUFFIX).trim().nullIfBlank()
                                            translated = right.endsWith(TR_SUFFIX, true)
                                        }

                                        "file size" -> size = parseHumanReadableByteCount(right)?.toLong()

                                        "length" -> length = right.removeSuffix("pages").trim().nullIfBlank()?.toInt()

                                        "favorited" -> favorites = right.removeSuffix("times").trim().nullIfBlank()?.toInt()
                                    }
                                }
                            }
                    }
            }

            ignore {
                averageRating = doc.select("#rating_label")
                    .text()
                    .removePrefix("Average:")
                    .trim()
                    .nullIfBlank()
                    ?.toDouble()
                ratingCount = doc.select("#rating_count")
                    .text()
                    .nullIfBlank()
                    ?.toInt()
            }

            doc.select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags = it.select("div").map { element ->
                    Tag(
                        element.text(),
                        element.hasClass("gtl"),
                    )
                }
                tags[namespace] = currentTags
            }

            SManga.create().apply {
                copyTo(this)
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageUrls = mutableListOf<String>()
        var next: String? = "$baseUrl${chapter.url}"
        while (next != null) {
            val doc = client.get(next).asJsoup()
            val hrefs = doc.select("#gdt a").map { it.attr("abs:href") }
            // Newer layout links every thumbnail to the multi-page viewer
            hrefs.firstOrNull { "/mpv/" in it }?.let { return getMpvPages(it) }
            pageUrls += hrefs
            next = nextPageUrl(doc)
        }
        return pageUrls.mapIndexed { index, url -> Page(index, url = url) }
    }

    private suspend fun getMpvPages(mpvHref: String): List<Page> {
        val scripts = client.get(mpvHref.substringBefore('#')).asJsoup()
            .select("script")
            .joinToString("\n") { it.data() }
        val gid = GID_REGEX.find(scripts)?.groupValues?.get(1)
            ?: throw Exception("Could not find gallery id on MPV page")
        val imageList = IMAGE_LIST_REGEX.find(scripts)?.groupValues?.get(1)
            ?: throw Exception("Could not find image list on MPV page")
        return imageList.parseAs<List<MpvImageDto>>().mapIndexed { index, image ->
            Page(index, url = "$baseUrl/s/${image.key}/$gid-${index + 1}")
        }
    }

    private fun nextPageUrl(element: Element) = element.select("a[onclick=return false]").last()?.let {
        if (it.text() == ">") it.attr("abs:href") else null
    }

    override suspend fun getImageUrl(page: Page): String = imageUrlParse(client.get(page.url), true)

    private fun imageUrlParse(response: Response, isGetBakImageUrl: Boolean): String {
        val doc = response.asJsoup()
        val imgUrl = doc.select("#img").attr("abs:src")
        val nlValue = NL_REGEX.find(doc.selectFirst("#loadfail")?.attr("onclick").orEmpty())?.groupValues?.get(1)

        if (getOriginalImagePref()) {
            val originalUrl = doc.selectFirst("a[href*=/fullimg/]")?.attr("abs:href")
            if (!originalUrl.isNullOrEmpty()) {
                return originalUrl.toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("nl", nlValue)
                    .build()
                    .toString()
            }
        }

        if (imgUrl.isEmpty()) {
            val reason = doc.body().text().take(200).ifEmpty { "empty response" }
            throw Exception("Could not find image on page: $reason")
        }

        if (!isGetBakImageUrl) return imgUrl
        if (nlValue.isNullOrEmpty()) return imgUrl
        val bakUrl = response.request.url.newBuilder()
            .addQueryParameter("nl", nlValue)
            .toString()
        return "$imgUrl#$bakUrl"
    }

    // Filters

    override fun getFilterList(data: JsonElement?) = FilterList(
        EnforceLanguageFilter(getEnforceLanguagePref()),
        Favorites(),
        Watched(),
        GenreGroup(),
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        Filter.Header("Use 'Female Tags' or 'Male Tags' for specific categories. 'Tags' searches all categories."),
        TextFilter("Tags", "tag"),
        TextFilter("Female Tags", "female"),
        TextFilter("Male Tags", "male"),
        AdvancedGroup(),
    )

    // Utilities

    private val languageMappings = listOf(
        Pair("japanese", listOf("0", "1024", "2048")),
        Pair("english", listOf("1", "1025", "2049")),
        Pair("chinese", listOf("10", "1034", "2058")),
        Pair("dutch", listOf("20", "1044", "2068")),
        Pair("french", listOf("30", "1054", "2078")),
        Pair("german", listOf("40", "1064", "2088")),
        Pair("hungarian", listOf("50", "1074", "2098")),
        Pair("italian", listOf("60", "1084", "2108")),
        Pair("korean", listOf("70", "1094", "2118")),
        Pair("polish", listOf("80", "1104", "2128")),
        Pair("portuguese", listOf("90", "1114", "2138")),
        Pair("russian", listOf("100", "1124", "2148")),
        Pair("spanish", listOf("110", "1134", "2158")),
        Pair("thai", listOf("120", "1144", "2168")),
        Pair("vietnamese", listOf("130", "1154", "2178")),
        Pair("n/a", listOf("254", "1278", "2302")),
        Pair("other", listOf("255", "1279", "2303")),
    )

    private val requestCookies by lazy {
        // "prn_n" hides the "popular right now" pane we can't parse; "xl_" excludes every language except ours
        val excludedLanguages = languageMappings.filter { it.first != ehLang }
            .flatMap { it.second }
            .joinToString("x")

        listOf(
            "uconfig" to "prn_n-xl_$excludedLanguages",
            // Bypass "Offensive For Everyone" content warning
            "nw" to "1",
            "ipb_member_id" to memberId,
            "ipb_pass_hash" to passHash,
            "igneous" to igneous,
        )
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor { chain -> proceedWithImageFallback(chain) }
        .addCookie { requestCookies }

    // Image urls carry the backup page url as a fragment: if the image fails to load, resolve a fresh one from it
    private fun proceedWithImageFallback(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val result = runCatching { chain.proceed(request) }
        val bakUrl = request.url.fragment ?: return result.getOrThrow()

        val response = result.getOrNull()
        val isUsableImage = response?.isSuccessful == true &&
            runCatching { response.hasValidImageHeader() }.getOrDefault(false)
        if (isUsableImage) return result.getOrThrow()

        val bakHttpUrl = bakUrl.toHttpUrlOrNull()
        val sourceHost = baseUrl.toHttpUrl().host
        if (bakHttpUrl == null ||
            !(bakHttpUrl.host == sourceHost || bakHttpUrl.host.endsWith(".$sourceHost"))
        ) {
            return result.getOrThrow()
        }
        response?.close()
        val bakRequest = Request.Builder().url(bakHttpUrl).headers(headers).build()
        val newImageUrl = try {
            imageUrlParse(chain.proceed(bakRequest), false)
        } catch (e: Exception) {
            result.exceptionOrNull()?.let { throw it }
            throw java.io.IOException("Image fallback failed (HTTP ${response?.code})", e)
        }
        return chain.proceed(request.newBuilder().url(newImageUrl).build())
    }

    private fun Response.hasValidImageHeader(): Boolean {
        val header = peekBody(IMAGE_HEADER_SIZE).bytes()
        return IMAGE_SIGNATURES.any { header.startsWith(it) } ||
            (header.startsWith(RIFF_SIGNATURE) && header.startsWith(WEBP_SIGNATURE, 8))
    }

    private fun ByteArray.startsWith(signature: ByteArray, offset: Int = 0): Boolean = size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

    @Suppress("SameParameterValue")
    private fun addParam(url: String, param: String, value: String) = Uri.parse(url)
        .buildUpon()
        .appendQueryParameter(param, value)
        .toString()

    internal open class TextFilter(name: String, val type: String, val specific: String = "") : Text(name)

    @Serializable
    class MpvImageDto(
        @SerialName("k") val key: String,
    )
    class Watched :
        CheckBox("Watched List"),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendPath("watched")
            }
        }
    }

    class Favorites :
        CheckBox("Favorites"),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendPath("favorites.php")
            }
        }
    }

    class GenreOption(name: String, private val genreId: String) :
        CheckBox(name, false),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            builder.appendQueryParameter("f_$genreId", if (state) "1" else "0")
        }
    }

    class GenreGroup :
        UriGroup<GenreOption>(
            "Genres",
            listOf(
                GenreOption("Dōjinshi", "doujinshi"),
                GenreOption("Manga", "manga"),
                GenreOption("Artist CG", "artistcg"),
                GenreOption("Game CG", "gamecg"),
                GenreOption("Western", "western"),
                GenreOption("Non-H", "non-h"),
                GenreOption("Image Set", "imageset"),
                GenreOption("Cosplay", "cosplay"),
                GenreOption("Asian Porn", "asianporn"),
                GenreOption("Misc", "misc"),
            ),
        )

    class AdvancedOption(name: String, private val param: String, defValue: Boolean = false) :
        CheckBox(name, defValue),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendQueryParameter(param, "on")
            }
        }
    }

    open class PageOption(name: String, private val queryKey: String) :
        Text(name),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state.isNotBlank()) {
                if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                    builder.appendQueryParameter("f_sp", "on")
                }

                builder.appendQueryParameter(queryKey, state.trim())
            }
        }
    }

    class MinPagesOption : PageOption("Minimum Pages", "f_spf")
    class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

    class RatingOption :
        Select<String>(
            "Minimum Rating",
            arrayOf(
                "Any",
                "2 stars",
                "3 stars",
                "4 stars",
                "5 stars",
            ),
        ),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state > 0) {
                builder.appendQueryParameter("f_srdd", (state + 1).toString())
                builder.appendQueryParameter("f_sr", "on")
            }
        }
    }

    // Explicit type arg for listOf() to workaround this: KT-16570
    class AdvancedGroup :
        UriGroup<Filter<*>>(
            "Advanced Options",
            listOf(
                AdvancedOption("Search Gallery Name", "f_sname", true),
                AdvancedOption("Search Gallery Tags", "f_stags", true),
                AdvancedOption("Search Gallery Description", "f_sdesc"),
                AdvancedOption("Search Torrent Filenames", "f_storr"),
                AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
                AdvancedOption("Search Low-Power Tags", "f_sdt1"),
                AdvancedOption("Search Downvoted Tags", "f_sdt2"),
                AdvancedOption("Show Expunged Galleries", "f_sh"),
                RatingOption(),
                MinPagesOption(),
                MaxPagesOption(),
            ),
        )

    private class EnforceLanguageFilter(default: Boolean) : CheckBox("Enforce language", default)

    companion object {
        const val QUERY_PREFIX = "?f_apply=Apply+Filter"
        const val PREFIX_ID_SEARCH = "id:"
        const val TR_SUFFIX = "TR"

        private const val MAX_CURSORS = 100

        private val NL_REGEX = Regex("nl\\('(.+?)'\\)")
        private val GID_REGEX = Regex("""var\s+gid\s*=\s*(\d+)""")
        private val IMAGE_LIST_REGEX = Regex("""var\s+imagelist\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)

        private const val IMAGE_HEADER_SIZE = 12L
        private val IMAGE_SIGNATURES = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "GIF87a".toByteArray(),
            "GIF89a".toByteArray(),
        )
        private val RIFF_SIGNATURE = "RIFF".toByteArray()
        private val WEBP_SIGNATURE = "WEBP".toByteArray()

        // Preferences vals
        private const val ENFORCE_LANGUAGE_PREF_KEY = "ENFORCE_LANGUAGE"
        private const val ENFORCE_LANGUAGE_PREF_TITLE = "Enforce Language"
        private const val ENFORCE_LANGUAGE_PREF_SUMMARY = "If checked, forces browsing of manga matching a language tag"
        private const val ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE = false

        private const val ORIGINAL_IMAGE_PREF_KEY = "ORIGINAL_IMAGE"
        private const val ORIGINAL_IMAGE_PREF_TITLE = "Original Image"
        private const val ORIGINAL_IMAGE_PREF_SUMMARY = "If checked, if your account has permission, it will use the original image and the image enhancement process will be slower"
        private const val ORIGINAL_IMAGE_PREF_DEFAULT_VALUE = false

        private const val MEMBER_ID_PREF_KEY = "MEMBER_ID"
        private const val MEMBER_ID_PREF_TITLE = "ipb_member_id"
        private const val MEMBER_ID_PREF_SUMMARY = "ipb_member_id value"
        private const val MEMBER_ID_PREF_DEFAULT_VALUE = ""

        private const val PASS_HASH_PREF_KEY = "PASS_HASH"
        private const val PASS_HASH_PREF_TITLE = "ipb_pass_hash"
        private const val PASS_HASH_PREF_SUMMARY = "ipb_pass_hash value"
        private const val PASS_HASH_PREF_DEFAULT_VALUE = ""

        private const val IGNEOUS_PREF_KEY = "IGNEOUS"
        private const val IGNEOUS_PREF_TITLE = "igneous"
        private const val IGNEOUS_PREF_SUMMARY = "igneous value override"
        private const val IGNEOUS_PREF_DEFAULT_VALUE = ""

        private const val FORCE_EH = "FORCE_EH"
        private const val FORCE_EH_TITLE = "Force e-hentai"
        private const val FORCE_EH_SUMMARY = "Force e-hentai to avoid content on exhentai"
        private const val FORCE_EH_DEFAULT_VALUE = true
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val forceEhPref = CheckBoxPreference(screen.context).apply {
            key = FORCE_EH
            title = FORCE_EH_TITLE
            summary = FORCE_EH_SUMMARY
            setDefaultValue(FORCE_EH_DEFAULT_VALUE)
        }

        val enforceLanguagePref = CheckBoxPreference(screen.context).apply {
            key = "${ENFORCE_LANGUAGE_PREF_KEY}_$lang"
            title = ENFORCE_LANGUAGE_PREF_TITLE
            summary = ENFORCE_LANGUAGE_PREF_SUMMARY
            setDefaultValue(ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)
        }

        val originalImagePref = CheckBoxPreference(screen.context).apply {
            key = "${ORIGINAL_IMAGE_PREF_KEY}_$lang"
            title = ORIGINAL_IMAGE_PREF_TITLE
            summary = ORIGINAL_IMAGE_PREF_SUMMARY
            setDefaultValue(ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)
        }

        val memberIdPref = EditTextPreference(screen.context).apply {
            key = MEMBER_ID_PREF_KEY
            title = MEMBER_ID_PREF_TITLE
            summary = MEMBER_ID_PREF_SUMMARY

            setDefaultValue(MEMBER_ID_PREF_DEFAULT_VALUE)
        }

        val passHashPref = EditTextPreference(screen.context).apply {
            key = PASS_HASH_PREF_KEY
            title = PASS_HASH_PREF_TITLE
            summary = PASS_HASH_PREF_SUMMARY

            setDefaultValue(PASS_HASH_PREF_DEFAULT_VALUE)
        }

        val igneousPref = EditTextPreference(screen.context).apply {
            key = IGNEOUS_PREF_KEY
            title = IGNEOUS_PREF_TITLE
            summary = IGNEOUS_PREF_SUMMARY

            setDefaultValue(IGNEOUS_PREF_DEFAULT_VALUE)
        }

        screen.addPreference(forceEhPref)
        screen.addPreference(memberIdPref)
        screen.addPreference(passHashPref)
        screen.addPreference(igneousPref)
        screen.addPreference(originalImagePref)
        screen.addPreference(enforceLanguagePref)
    }

    private fun getEnforceLanguagePref(): Boolean = preferences.getBoolean("${ENFORCE_LANGUAGE_PREF_KEY}_$lang", ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)

    private fun getOriginalImagePref(): Boolean = preferences.getBoolean("${ORIGINAL_IMAGE_PREF_KEY}_$lang", ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)

    private fun getCookieValue(cookieTitle: String, defaultValue: String, prefKey: String): String {
        val cookies = webViewCookieManager.getCookie("https://forums.e-hentai.org")
        var value: String? = null

        if (cookies != null) {
            val cookieArray = cookies.split("; ")
            for (cookie in cookieArray) {
                if (cookie.startsWith("$cookieTitle=")) {
                    value = cookie.split("=")[1]

                    break
                }
            }
        }

        if (value == null) {
            value = preferences.getString(prefKey, defaultValue) ?: defaultValue
        }

        return value
    }

    private fun getPassHashPref(): String = getCookieValue(PASS_HASH_PREF_TITLE, PASS_HASH_PREF_DEFAULT_VALUE, PASS_HASH_PREF_KEY)

    private fun getMemberIdPref(): String = getCookieValue(MEMBER_ID_PREF_TITLE, MEMBER_ID_PREF_DEFAULT_VALUE, MEMBER_ID_PREF_KEY)

    private fun getIgneousPref(): String = getCookieValue(IGNEOUS_PREF_TITLE, IGNEOUS_PREF_DEFAULT_VALUE, IGNEOUS_PREF_KEY)

    private fun getForceEhPref(): Boolean = preferences.getBoolean(FORCE_EH, FORCE_EH_DEFAULT_VALUE)
}
