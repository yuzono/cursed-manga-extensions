package eu.kanade.tachiyomi.extension.en.hentai2read

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.intOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Hentai2Read :
    KeiSource(),
    ConfigurableSource {

    override val supportsFilterFetching = true

    private val preferences by getPreferencesLazy()

    // Only the site itself; reader images come from the CDN.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        val host = baseUrl.toHttpUrl().host
        return rateLimit(permits = 2, period = 1.seconds) { it.host == host }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = client.get(rankingUrl(preferences.getString(PREF_POPULAR_PERIOD, "").orEmpty(), page), Fetch.DOCUMENT).asJsoup().toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(listingUrl("/browse", page), Fetch.DOCUMENT).asJsoup().toMangasPage()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // Callers can pass an empty list instead of the source's filters.
        val filterList = filters.ifEmpty { getFilterList() }
        val tags = filterList.filterIsInstance<TagGroup>().flatMap { it.state }
        val artists = filterList.filterIsInstance<ArtistGroup>().flatMap { it.state }.filter { it.state }
        val includedTags = tags.filter { it.isIncluded() }
        val included = includedTags.map { it.id } + artists.map { it.id }
        val excluded = tags.filter { it.isExcluded() }.map { it.id }
        val selectedPages = includedTags.map { it.url } + artists.map { it.url }
        val ranking = filterList.firstInstance<RankingFilter>().period

        // Search text always searches. Without it, a ranking or a single included tag/artist opens that page.
        val url = when {
            query.isBlank() && ranking != null -> rankingUrl(ranking, page)
            query.isBlank() && excluded.isEmpty() && selectedPages.size == 1 -> tagPageUrl(selectedPages.single(), filterList, page)
            else -> "$baseUrl/search".toHttpUrl().newBuilder().apply {
                if (query.isNotBlank()) addQueryParameter("q", query)
                filterList.firstInstance<TypeFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("type", it) }
                filterList.firstInstance<StatusFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("status", it) }
                filterList.firstInstance<SortFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("sort", it) }
                if (filterList.firstInstance<MatchAnyFilter>().state) addQueryParameter("match", "any")
                if (included.isNotEmpty()) addQueryParameter("with", included.joinToString(","))
                if (excluded.isNotEmpty()) addQueryParameter("without", excluded.joinToString(","))
                if (page > 1) addQueryParameter("page", page.toString())
            }.build().toString()
        }

        return client.get(url, Fetch.DOCUMENT).asJsoup().toMangasPage()
    }

    // Unlike the other listings, rankings page with ?page=N.
    private fun rankingUrl(period: String, page: Int): String = "$baseUrl/ranking".toHttpUrl().newBuilder().apply {
        if (period.isNotEmpty()) addQueryParameter("period", period)
        if (page > 1) addQueryParameter("page", page.toString())
    }.build().toString()

    // A tag page takes Type, Status and Sort as path segments, e.g. /category/18-adult/doujinshi/completed/newest.
    private fun tagPageUrl(path: String, filters: FilterList, page: Int): String {
        val segments = listOf(
            filters.firstInstance<TypeFilter>().value,
            filters.firstInstance<StatusFilter>().value,
            // "Recently updated" is a tag page's default order, so it has no segment.
            filters.firstInstance<SortFilter>().value.takeUnless { it == "latest" }.orEmpty(),
        ).filter { it.isNotEmpty() }
        return listingUrl((listOf(path) + segments).joinToString("/"), page)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val document = client.get(url.newBuilder().scheme("https").build(), Fetch.DOCUMENT).asJsoup()
        // A reader page's canonical link is its book page.
        if (url.pathSegments.firstOrNull() == "reader") {
            return document.selectFirst("link[rel=canonical]")?.absUrl("href")?.toHttpUrlOrNull()
                ?.takeIf { it.pathSegments.firstOrNull() != "reader" }
                ?.let { getMangaByUrl(it) }
        }
        val book = document.parsePageDataOrNull<BookPageDto>() ?: return null
        return book.toSManga(document.singleChapterPages(book))
    }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host != baseUrl.toHttpUrl().host) return MangasPage(emptyList(), false)

        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val listing = when {
            segments.firstOrNull() == "search" -> url.newBuilder().scheme("https").setQueryParameter("page", page.toString()).build().toString()
            segments.firstOrNull() == "ranking" -> rankingUrl(url.queryParameter("period").orEmpty(), page)
            // Keep the link's other segments (/browse/trending, a tag's /doujinshi/completed); its /page-N gives way to the requested page.
            segments.firstOrNull() == "browse" || (segments.size >= 2 && segments[0] in LISTING_TYPES) ->
                listingUrl("/" + segments.dropLastWhile { PAGE_SEGMENT_REGEX.matches(it) }.joinToString("/"), page)
            else -> return MangasPage(listOfNotNull(getMangaByUrl(url)), false)
        }
        return client.get(listing, Fetch.DOCUMENT).asJsoup().toMangasPage()
    }

    private fun listingUrl(path: String, page: Int) = if (page > 1) "$baseUrl$path/page-$page" else "$baseUrl$path"

    // Logged in, the site sends pages without their rendered HTML (the browser builds it), so only the page data is read.
    private fun Document.toMangasPage(): MangasPage {
        val listing = parsePageData<ListingDto>()
        return MangasPage(listing.books.map { it.toSManga() }, listing.hasNextPage)
    }

    // Details and chapters share one page, so both are always returned.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        val book = document.parsePageDataOrNull<BookPageDto>()
            ?: throw Exception("Manga page not found. Migrate or re-add this entry.")

        // Multi-chapter books have no count on their own page: a details refresh looks up the current one,
        // otherwise the count from browsing or an earlier refresh is kept.
        val pages = document.singleChapterPages(book)
            ?: (if (fetchDetails) fetchPagesFromSearch(book) else null)
            ?: manga.memo[MEMO_PAGE_COUNT]?.intOrNull

        val parsedChapters = parseChapters(book)

        return SMangaUpdate(
            manga = book.toSManga(pages).apply {
                url = manga.url
                // Saved so a later chapters-only refresh doesn't fall back to an older count from browsing.
                memo = pages?.let { JsonObject(manga.memo + (MEMO_PAGE_COUNT to JsonPrimitive(it))) } ?: manga.memo
                if (status == SManga.COMPLETED && parsedChapters.size == 1) {
                    update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
                }
            },
            chapters = parsedChapters,
        )
    }

    // The book id comes from the page, so entries stored under pre-redesign URLs are found too.
    private suspend fun fetchPagesFromSearch(book: BookPageDto): Int? {
        val id = book.id
        return try {
            val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("q", book.title).build()
            // An error page (e.g. a Cloudflare 403) has no page data, so it yields null instead of throwing.
            client.get(url, Fetch.DOCUMENT, ensureSuccess = false).asJsoup().parsePageDataOrNull<ListingDto>()?.books.orEmpty()
                .firstOrNull { it.id == id }?.pageCount
        } catch (_: IOException) {
            null // a failed lookup shouldn't break the details refresh
        }
    }

    // Only single-chapter books have numberOfPages in their JSON-LD.
    private fun Document.singleChapterPages(book: BookPageDto): Int? {
        if (book.chapters.size != 1) return null
        return runCatching {
            selectFirst("script[type=application/ld+json]")
                ?.data()
                ?.parseAs<List<JsonLdDto>>()
                ?.firstNotNullOfOrNull { it.bookPages }
        }.getOrNull()
    }

    private fun parseChapters(book: BookPageDto): List<SChapter> = book.chapters.map { chapter ->
        val number = chapter.number?.takeIf { it.isNotEmpty() }
        val label = listOfNotNull(number?.let { "Ch. $it" }, chapter.title?.ifEmpty { null }).joinToString(" - ").ifEmpty { "Chapter" }
        SChapter.create().apply {
            setUrlWithoutDomain(chapter.url)
            name = if (chapter.isMembers) "🔒 $label" else label
            chapter_number = number?.toFloatOrNull() ?: -1f
            date_upload = dateFormat.tryParseDate(chapter.date, ZoneOffset.UTC)
        }
    }

    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()
        .parsePageData<BookPageDto>().related.map { it.toSManga() }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Pre-redesign chapter URLs (/slug/N/) don't redirect to the new reader.
        if (!chapter.url.startsWith("/reader/")) throw Exception("Outdated chapter link. Refresh the chapter list.")
        val document = client.get("$baseUrl${chapter.url}", Fetch.DOCUMENT).asJsoup()
        return document.parsePageData<ReaderDto>().toPages(preferAvif = hasNewDecoder)
    }

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        (TAG_TYPES.map { type -> async { fetchTags(type) } } + async { fetchTopArtists() }).awaitAll().flatten()
    }.toJsonElement()

    private suspend fun fetchTopArtists(): List<TagDto> = client.get("$baseUrl/tags/artist", Fetch.DOCUMENT).asJsoup()
        .parsePageData<TagPageDto>().tags
        .filter { it.name != "Unknown" }

    private suspend fun fetchTags(type: String): List<TagDto> {
        val first = client.get("$baseUrl/tags/$type", Fetch.DOCUMENT).asJsoup().parsePageData<TagPageDto>()
        val rest = coroutineScope {
            (2..minOf(first.lastPage, MAX_TAG_PAGES)).map { page ->
                async { client.get("$baseUrl/tags/$type/page-$page", Fetch.DOCUMENT).asJsoup().parsePageData<TagPageDto>().tags }
            }.awaitAll()
        }.flatten()
        return first.tags + rest
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val tags = data?.parseAs<List<TagDto>>().orEmpty()
        return hentai2readFilters(tags)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_POPULAR_PERIOD
            title = "Popular period"
            entries = RANKING_PERIODS.map { it.first }.toTypedArray()
            entryValues = RANKING_PERIODS.map { it.second }.toTypedArray()
            summary = "%s"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    // Animated avif plays with Mihon's new decoder (v0.21.0+), older forks only show a still frame
    private val hasNewDecoder by lazy { runCatching { Class.forName("ca.mpreg.imagedecoder.ImageDecoder") }.isSuccess }

    private inline fun <reified T> Document.parsePageData(): T {
        val script = selectFirst("script[data-page=app]")?.data()
            ?: throw Exception("Page data not found")
        return script.parseAs<T>()
    }

    private inline fun <reified T> Document.parsePageDataOrNull(): T? = runCatching { parsePageData<T>() }.getOrNull()

    companion object {
        private const val MAX_TAG_PAGES = 10

        private const val PREF_POPULAR_PERIOD = "popular_period"

        private val LISTING_TYPES = setOf("category", "content", "artist", "parody", "character", "language")
        private val PAGE_SEGMENT_REGEX = Regex("""page-\d+""")

        private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    }
}
