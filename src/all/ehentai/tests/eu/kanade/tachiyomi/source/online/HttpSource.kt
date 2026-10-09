package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

// The published extension API throws "Stub!" in its constructor. This test-only
// host supplies the API boundary while requests and parsers run in the real source.
abstract class HttpSource : CatalogueSource {
    abstract val baseUrl: String
    override val id = 1713178126840476467L
    val headers: Headers = Headers.Builder().add("User-Agent", "EHentai test host").build()
    protected val network: NetworkHelper get() = error("Host network is not used by parser tests")
    open val client: OkHttpClient get() = error("Host network is not used by parser tests")

    abstract fun popularMangaRequest(page: Int): Request
    abstract fun popularMangaParse(response: Response): MangasPage
    abstract fun latestUpdatesRequest(page: Int): Request
    abstract fun latestUpdatesParse(response: Response): MangasPage
    abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    abstract fun searchMangaParse(response: Response): MangasPage
    abstract fun mangaDetailsParse(response: Response): SManga
    open fun chapterListRequest(manga: SManga): Request = error("Not used")
    abstract fun chapterListParse(response: Response): List<SChapter>
    abstract fun pageListParse(response: Response): List<Page>
    abstract fun imageUrlParse(response: Response): String

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = error("Not used")
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = error("Not used")
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = error("Not used")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = error("Not used")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = error("Not used")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = error("Not used")
}
