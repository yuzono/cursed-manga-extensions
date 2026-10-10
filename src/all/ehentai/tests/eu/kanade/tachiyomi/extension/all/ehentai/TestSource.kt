package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal class TestSource(
    override val baseUrl: String = "https://exhentai.org",
    override val client: OkHttpClient = OkHttpClient(),
) : EHentai() {
    override val name = "E-Hentai"
    override val lang = "all"

    public override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = super.searchMangaRequest(page, query, filters)
    public override fun searchMangaParse(response: Response) = super.searchMangaParse(response)
    public override fun latestUpdatesRequest(page: Int) = super.latestUpdatesRequest(page)
    public override fun latestUpdatesParse(response: Response) = super.latestUpdatesParse(response)
    public override fun popularMangaRequest(page: Int) = super.popularMangaRequest(page)
    public override fun popularMangaParse(response: Response) = super.popularMangaParse(response)
    public override fun mangaDetailsParse(response: Response) = super.mangaDetailsParse(response)
    public override fun chapterListParse(response: Response) = super.chapterListParse(response)
    public override fun imageUrlParse(response: Response) = response.asJsoup().galleryImageUrl(response.request.url, false, true)
}

internal fun htmlResponse(request: Request, html: String): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(200)
    .message("OK")
    .body(html.toResponseBody("text/html".toMediaType()))
    .build()
