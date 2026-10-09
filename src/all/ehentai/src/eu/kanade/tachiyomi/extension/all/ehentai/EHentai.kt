package eu.kanade.tachiyomi.extension.all.ehentai

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
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParseDateTime
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import rx.schedulers.Schedulers
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
    private val galleryPages = GalleryPageLoader()

    private fun genericMangaParse(response: Response, pagination: GalleryPagination? = null): MangasPage {
        val doc = response.asJsoup()
        val listing = GalleryList(doc)
        val hasNextPage = pagination?.update(response.request, listing.nextPageUrl) == true
        return MangasPage(listing.galleries, hasNextPage)
    }

    override fun chapterListRequest(manga: SManga) = exGet("$baseUrl${manga.url}")

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = galleryPage(mangaDetailsRequest(manga), GalleryPageUse.DETAILS)
        .map { mangaDetailsParse(it).apply { initialized = true } }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = galleryPage(chapterListRequest(manga), GalleryPageUse.CHAPTERS)
        .map(::chapterListParse)

    override fun chapterListParse(response: Response): List<SChapter> = chapterListParse(response.asJsoup())

    private fun chapterListParse(document: Document): List<SChapter> = listOf(
        SChapter.create().apply {
            url = ExGalleryMetadata.normalizeUrl(document.location().toHttpUrl().encodedPath)
            name = "Chapter"
            chapter_number = 1f
            date_upload = document.galleryPostedDate()
        },
    )

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = galleryPage(exGet("$baseUrl${chapter.url}"), GalleryPageUse.READER)
        .map { it.galleryReaderPages() }

    override fun fetchImageUrl(page: Page): Observable<String> {
        val url = page.url.toHttpUrl()
        val imagePage = if (url.pathSegments.firstOrNull() == "g") {
            galleryPage(exGet(page.url), GalleryPageUse.IMAGE_PAGES)
                .map { it.galleryImagePage(page.index) }
        } else {
            Observable.just(page.url)
        }
        return imagePage.flatMap { chapterPageCall(exGet(it)).map(::imageUrlParse) }
    }

    private fun galleryPage(request: Request, use: GalleryPageUse): Observable<Document> {
        val key = request.url.newBuilder().removeAllQueryParameters("nw").build().toString()
        return galleryPages.load(key, use) {
            chapterPageCall(request).map { response ->
                val document = response.asJsoup()
                if (use == GalleryPageUse.IMAGE_PAGES) {
                    val previews = document.getElementById("gdt")
                    check(previews?.selectFirst("a[href]") != null) { "No image pages found" }
                    // Retain only the previews needed for reading, not the gallery's tags and comments.
                    Document(document.location()).apply { body().appendChild(previews) }
                } else {
                    document
                }
            }
        }
    }

    private fun chapterPageCall(request: Request) = client.newCall(request).asObservableSuccess()
        .subscribeOn(Schedulers.io())

    // The website's Popular list has no next-page link.
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

    override fun mangaDetailsParse(response: Response) = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document) = with(document) {
        with(ExGalleryMetadata()) {
            url = ExGalleryMetadata.normalizeUrl(document.location().toHttpUrl().encodedPath)
            title = getElementById("gn")?.text().nullIfBlank()

            altTitle = getElementById("gj")?.text().nullIfBlank()

            // Thumbnail is set as background of element in style attribute
            thumbnailUrl = selectFirst("#gd1 div")?.attr("style").nullIfBlank()?.let {
                it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
            }
            category = selectFirst("#gdc div")?.text().nullIfBlank()?.lowercase()

            uploader = getElementById("gdn")?.text().nullIfBlank()

            select("#gdd tr").forEach { row ->
                val label = row.selectFirst(".gdt1")?.text()?.removeSuffix(":")?.lowercase()
                val value = row.selectFirst(".gdt2")?.text().nullIfBlank() ?: return@forEach
                when (label) {
                    "posted" -> datePosted = EX_DATE_FORMAT.tryParseDateTime(value, ZoneOffset.UTC)
                    "visible" -> visible = value
                    "language" -> {
                        language = value.removeSuffix(TR_SUFFIX).trim().nullIfBlank()
                        translated = value.endsWith(TR_SUFFIX, true)
                    }
                    "file size" -> size = parseHumanReadableByteCount(value)?.toLong()
                    "length" -> length = value.substringBefore(' ').replace(",", "").toIntOrNull()
                    "favorited" -> favorites = value.substringBefore(' ').replace(",", "").toIntOrNull()
                }
            }

            averageRating = getElementById("rating_label")?.text()?.removePrefix("Average:")?.trim()?.toDoubleOrNull()
            ratingCount = getElementById("rating_count")?.text()?.replace(",", "")?.toIntOrNull()

            // Parse tags
            select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags = it.select("div").map { element ->
                    Tag(
                        element.text(),
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

    private fun searchMangaByIdRequest(id: String) = exGet("$baseUrl/g/${id.trimEnd('/')}/")

    private fun searchMangaByIdParse(document: Document): MangasPage {
        val details = mangaDetailsParse(document)
        details.initialized = true
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
        galleryPage(searchMangaByIdRequest(id), GalleryPageUse.DETAILS)
            .map(::searchMangaByIdParse)
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
            .addGalleryImageRetry({ headers }) { imageUrlParse(it, false) }
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

    internal class TextFilter(name: String, val type: String) : Text(name)

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
