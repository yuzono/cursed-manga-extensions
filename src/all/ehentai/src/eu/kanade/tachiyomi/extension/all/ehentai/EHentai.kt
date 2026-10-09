package eu.kanade.tachiyomi.extension.all.ehentai

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
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
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParseDateTime
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.time.ZoneOffset

@Source
abstract class EHentai :
    HttpSource(),
    ConfigurableSource {

    private val legacySettings by lazy {
        legacyGallerySources.map { (language, sourceId) ->
            legacyGallerySettings(language, getPreferences(sourceId).all)
        }.filter { it.values.isNotEmpty() }
    }

    private val preferences: SharedPreferences by getPreferencesLazy {
        if (!getBoolean(LEGACY_SETTINGS_IMPORTED, false)) {
            gallerySettingsToMigrate(all, legacySettings)?.let(::importGallerySettings)
        }
    }

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }
    private val forceEh: Boolean get() = getForceEhPref()

    override val baseUrl: String
        get() {
            if (System.getenv("CI") == "true" || forceEh) return "https://e-hentai.org"
            val credentials = getGalleryCredentials(false)
            return if (credentials.memberId.isNotEmpty() && credentials.passHash.isNotEmpty()) {
                "https://exhentai.org"
            } else {
                "https://e-hentai.org"
            }
        }

    override val supportsLatest = true

    override fun toString() = name

    private val latestPagination = GalleryPagination()
    private val searchPagination = GalleryPagination()

    private fun genericMangaParse(response: Response, pagination: GalleryPagination? = null): MangasPage {
        val doc = response.asJsoup()
        val listing = GalleryList(doc)
        pagination?.update(response.request, listing.nextPageUrl)
        return MangasPage(listing.galleries.map { it.toSManga() }, pagination != null && listing.nextPageUrl != null)
    }

    override fun chapterListRequest(manga: SManga) = exGet("$baseUrl${manga.url}")

    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            url = ExGalleryMetadata.normalizeUrl(response.request.url.encodedPath)
            name = "Chapter"
            chapter_number = 1f
            date_upload = response.asJsoup().galleryPostedDate()
        },
    )

    override fun fetchPageList(chapter: SChapter) = fetchChapterPage(chapter, "$baseUrl${chapter.url}").map {
        it.mapIndexed { i, s ->
            Page(i, s)
        }
    }!!

    /**
     * Recursively fetch chapter pages
     */
    private fun fetchChapterPage(
        chapter: SChapter,
        np: String,
        pastUrls: List<String> = emptyList(),
    ): Observable<List<String>> {
        val urls = ArrayList(pastUrls)
        return chapterPageCall(np).flatMap {
            val jsoup = it.asJsoup()
            urls += jsoup.galleryImagePages()
            jsoup.nextGalleryPageUrl()?.let { string ->
                fetchChapterPage(chapter, string, urls)
            } ?: Observable.just(urls)
        }
    }

    private fun chapterPageCall(np: String) = client.newCall(chapterPageRequest(np)).asObservableSuccess()
    private fun chapterPageRequest(np: String) = exGet(np)

    override fun popularMangaRequest(page: Int) = exGet("$baseUrl/popular")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val firstPageUrl = gallerySearchUrl(baseUrl, query, filters)
        if (firstPageUrl.encodedPath == "/popular") return exGet(firstPageUrl.toString())
        return searchPagination.request(exGet(firstPageUrl.toString()), page)
    }

    override fun latestUpdatesRequest(page: Int) = latestPagination.request(exGet(baseUrl), page)

    override fun popularMangaParse(response: Response) = genericMangaParse(response)
    override fun searchMangaParse(response: Response) = genericMangaParse(response, searchPagination.takeUnless { response.request.url.encodedPath == "/popular" })
    override fun latestUpdatesParse(response: Response) = genericMangaParse(response, latestPagination)

    private fun exGet(url: String): Request = GET(url, headers)

    /**
     * Parse gallery page to metadata model
     */
    @SuppressLint("DefaultLocale")
    override fun mangaDetailsParse(response: Response) = with(response.asJsoup()) {
        with(ExGalleryMetadata()) {
            url = ExGalleryMetadata.normalizeUrl(response.request.url.encodedPath)
            title = select("#gn").text().nullIfBlank()?.trim()

            altTitle = select("#gj").text().nullIfBlank()?.trim()

            // Thumbnail is set as background of element in style attribute
            thumbnailUrl = select("#gd1 div").attr("style").nullIfBlank()?.let {
                it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
            }
            category = select("#gdc div").text().nullIfBlank()?.trim()?.lowercase()

            uploader = select("#gdn").text().nullIfBlank()?.trim()

            // Parse the table
            select("#gdd tr").forEach {
                it.select(".gdt1")
                    .text()
                    .nullIfBlank()
                    ?.trim()
                    ?.let { left ->
                        it.select(".gdt2")
                            .text()
                            .nullIfBlank()
                            ?.trim()
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

            // Parse ratings
            ignore {
                averageRating = select("#rating_label")
                    .text()
                    .removePrefix("Average:")
                    .trim()
                    .nullIfBlank()
                    ?.toDouble()
                ratingCount = select("#rating_count")
                    .text()
                    .trim()
                    .nullIfBlank()
                    ?.toInt()
            }

            // Parse tags
            tags.clear()
            select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags = it.select("div").map { element ->
                    Tag(
                        element.text().trim(),
                        element.hasClass("gtl"),
                    )
                }
                tags[namespace] = currentTags
            }

            // Copy metadata to manga
            SManga.create().apply {
                copyTo(this)
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = ExGalleryMetadata.normalizeUrl("/g/$id/")
        return MangasPage(listOf(details), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith("https://")) {
        val url = query.toHttpUrl()
        if (url.pathSegments.size < 3) {
            throw Exception("Unsupported url")
        }
        val id = url.pathSegments[1]
        val key = url.pathSegments[2]
        fetchSearchManga(page, "${PREFIX_ID_SEARCH}$id/$key", filters)
    } else if (query.startsWith(PREFIX_ID_SEARCH)) {
        val id = query.removePrefix(PREFIX_ID_SEARCH)
        client.newCall(searchMangaByIdRequest(id))
            .asObservableSuccess()
            .map { response -> searchMangaByIdParse(response, id) }
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    override fun pageListParse(response: Response) = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = imageUrlParse(response, true)

    private fun imageUrlParse(response: Response, isGetBakImageUrl: Boolean): String = response.asJsoup()
        .galleryImageUrl(response.request.url, getOriginalImagePref(), isGetBakImageUrl)

    override val client by lazy {
        network.client.newBuilder()
            .addGalleryCookies(::getGalleryCredentials)
            .addInterceptor { chain ->
                val request = chain.request()
                val result = runCatching { chain.proceed(request) }
                val bakUrl = request.url.fragment
                    ?: return@addInterceptor result.getOrThrow()

                if (result.isFailure || result.getOrNull()?.isSuccessful != true) {
                    result.getOrNull()?.close()
                    val newRequest = GET(bakUrl, headers)
                    val newImageUrl = imageUrlParse(chain.proceed(newRequest), false)
                    val newImageRequest = request.newBuilder()
                        .url(newImageUrl)
                        .build()

                    chain.proceed(newImageRequest)
                } else {
                    result.getOrThrow()
                }
            }
            .build()
    }

    // Filters
    override fun getFilterList() = FilterList(
        GalleryListFilter(),
        GalleryLanguageFilter(),
        GenreGroup(),
        TextFilter("Tags", "tag"),
        TextFilter("Female Tags", "female"),
        TextFilter("Male Tags", "male"),
        AdvancedGroup(),
    )

    internal open class TextFilter(name: String, val type: String, val specific: String = "") : Text(name)

    class GenreOption(name: String, val mask: Int) : CheckBox(name, false)

    class GenreGroup :
        UriGroup<GenreOption>(
            "Categories",
            listOf(
                GenreOption("Dōjinshi", 2),
                GenreOption("Manga", 4),
                GenreOption("Artist CG", 8),
                GenreOption("Game CG", 16),
                GenreOption("Western", 512),
                GenreOption("Non-H", 256),
                GenreOption("Image Set", 32),
                GenreOption("Cosplay", 64),
                GenreOption("Asian Porn", 128),
                GenreOption("Misc", 1),
            ),
        ) {
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state.any { it.state }) {
                builder.addQueryParameter("f_cats", state.filterNot { it.state }.sumOf { it.mask }.toString())
            }
        }
    }

    class AdvancedOption(name: String, private val param: String, defValue: Boolean = false) :
        CheckBox(name, defValue),
        UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state) {
                builder.addQueryParameter(param, "on")
            }
        }
    }

    open class PageOption(name: String, private val queryKey: String) :
        Text(name),
        UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state.isNotBlank()) {
                builder.addQueryParameter(queryKey, state.trim())
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
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state > 0) {
                builder.addQueryParameter("f_srdd", (state + 1).toString())
            }
        }
    }

    // Explicit type arg for listOf() to workaround this: KT-16570
    class AdvancedGroup :
        UriGroup<Filter<*>>(
            "Advanced Options",
            listOf(
                AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
                AdvancedOption("Show Expunged Galleries", "f_sh"),
                RatingOption(),
                MinPagesOption(),
                MaxPagesOption(),
            ),
        )

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        const val TR_SUFFIX = "TR"
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Run migration before the host writes defaults for these preferences.
        val needsImport = !preferences.getBoolean(LEGACY_SETTINGS_IMPORTED, false)

        val forceEhPref = CheckBoxPreference(screen.context).apply {
            key = FORCE_EH
            title = "Force e-hentai"
            summary = "Force e-hentai to avoid content on exhentai"
            setDefaultValue(true)
        }

        val originalImagePref = CheckBoxPreference(screen.context).apply {
            key = ORIGINAL_IMAGE
            title = "Original Image"
            summary = "Use original images when your account permits it; images may load more slowly"
            setDefaultValue(false)
        }

        val memberIdPref = EditTextPreference(screen.context).apply {
            key = MEMBER_ID
            title = "ipb_member_id"
            setDefaultValue("")
        }

        val passHashPref = EditTextPreference(screen.context).apply {
            key = PASS_HASH
            title = "ipb_pass_hash"
            setDefaultValue("")
        }

        val igneousPref = EditTextPreference(screen.context).apply {
            key = IGNEOUS
            title = "igneous"
            setDefaultValue("")
        }

        if (needsImport) {
            screen.addPreference(
                ListPreference(screen.context).apply {
                    key = "LEGACY_SETTINGS_SOURCE"
                    title = "Import previous source settings"
                    entries = legacySettings.map { it.language }.toTypedArray()
                    entryValues = entries
                    setDefaultValue("")
                    setOnPreferenceChangeListener { _, value ->
                        preferences.importGallerySettings(legacySettings.single { it.language == value }.values)
                        forceEhPref.isChecked = getForceEhPref()
                        originalImagePref.isChecked = getOriginalImagePref()
                        memberIdPref.text = preferences.getString(MEMBER_ID, "")
                        passHashPref.text = preferences.getString(PASS_HASH, "")
                        igneousPref.text = preferences.getString(IGNEOUS, "")
                        true
                    }
                },
            )
        }

        screen.addPreference(forceEhPref)
        screen.addPreference(memberIdPref)
        screen.addPreference(passHashPref)
        screen.addPreference(igneousPref)
        screen.addPreference(originalImagePref)
    }

    private fun getOriginalImagePref(): Boolean = preferences.getBoolean(ORIGINAL_IMAGE, false)

    private fun getGalleryCredentials(forceEh: Boolean, requestCookie: String? = null): GalleryCredentials = galleryCredentials(forceEh, webViewCookieManager::getCookie, requestCookie) { cookieTitle ->
        val preferenceKey = when (cookieTitle) {
            "ipb_member_id" -> MEMBER_ID
            "ipb_pass_hash" -> PASS_HASH
            else -> IGNEOUS
        }
        preferences.getString(preferenceKey, "").orEmpty()
    }

    private fun getForceEhPref(): Boolean = preferences.getBoolean(FORCE_EH, true)
}
