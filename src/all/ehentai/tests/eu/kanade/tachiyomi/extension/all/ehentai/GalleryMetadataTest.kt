package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryMetadataTest {
    @Test
    fun listingIdSearchUrlSearchAndDetailsUseTheSameGalleryUrl() {
        GalleryTestServer(imageCount = 1).use { server ->
            val source = TestSource(server.baseUrl)
            val expected = "/g/1/token/?nw=always"
            val listing = GalleryList(
                Jsoup.parse("<table class='itg'><tr><td><a href='/g/1/token/'><div class='glink'>Gallery</div></a></td></tr></table>", server.baseUrl),
            ).galleries.single()
            assertEquals(expected, listing.url)
            assertEquals("1", ExGalleryMetadata.galleryId(listing.url))
            assertEquals(expected, source.fetchMangaDetails(listing).toBlocking().single().url)
            assertEquals(expected, source.fetchSearchManga(1, "id:1/token", FilterList()).toBlocking().single().mangas.single().url)
            assertEquals(expected, source.fetchSearchManga(1, "https://exhentai.org/g/1/token/", FilterList()).toBlocking().single().mangas.single().url)
            assertEquals(expected, source.fetchChapterList(listing).toBlocking().single().single().url)
        }
    }

    @Test
    fun artistTagsPopulateTheAuthorShownByTheReaderAndOverrideTitleCredits() {
        val response = htmlResponse(
            Request.Builder().url("https://exhentai.org/g/1/abcdef/").build(),
            """
            <h1 id="gn">[Title credit] Example gallery</h1>
            <div id="taglist"><table><tr><td class="tc">artist:</td>
              <td><div class="gt">artist one</div><div class="gtl">artist two</div></td>
            </tr></table></div>
            """,
        )
        val manga = response.use(TestSource()::mangaDetailsParse)
        assertEquals("artist one, artist two", manga.author)
        assertEquals(manga.author, manga.artist)
    }

    @Test
    fun untaggedCreatorsUseTheGalleryTitleCreditInsteadOfTheUploader() {
        val response = htmlResponse(
            Request.Builder().url("https://exhentai.org/g/1/abcdef/").build(),
            """<h1 id="gn">[Toratora] Example gallery [AI Generated]</h1><div id="gdn">Uploader</div>""",
        )
        val manga = response.use(TestSource()::mangaDetailsParse)
        assertEquals("Toratora", manga.author)
        assertEquals("Creator", galleryCreator("(C100) [Circle (Creator)] Title [English] [Translator]", emptyList()))
        assertNull(galleryCreator("[Anthology] Example gallery", emptyList()))
        assertNull(galleryCreator("Example gallery [English] [Translator]", emptyList()))
    }

    @Test
    fun galleryLanguageComesFromItsDetailsEvenWhenTheSourceSupportsAllLanguages() {
        val source = TestSource()
        assertEquals("all", source.lang)
        assertEquals("E-Hentai", source.toString())
        listOf("Chinese", "English", "Japanese").forEach { language ->
            val response = htmlResponse(
                Request.Builder().url("https://exhentai.org/g/1/abcdef/").build(),
                """
                <h1 id="gn">Example gallery</h1>
                <div id="gdd"><table><tr>
                  <td class="gdt1">Language:</td><td class="gdt2">$language <span>TR</span></td>
                </tr></table></div>
                <div id="taglist"><table><tr>
                  <td class="tc">language:</td><td><div class="gt">${language.lowercase()}</div></td>
                </tr></table></div>
                """,
            )
            val manga = response.use(source::mangaDetailsParse)
            assertTrue(manga.description.orEmpty().contains("Language: $language TR"))
            assertFalse(manga.description.orEmpty().contains("Language: all", ignoreCase = true))
            assertEquals("language:${language.lowercase()}", manga.genre)
        }
    }

    @Test
    fun missingGalleryLanguageDoesNotFallBackToTheSourceLanguage() {
        val response = htmlResponse(
            Request.Builder().url("https://exhentai.org/g/1/abcdef/").build(),
            """<h1 id="gn">Example gallery</h1><div id="gdd"><table></table></div>""",
        )
        val manga = response.use(TestSource()::mangaDetailsParse)
        assertFalse(manga.description.orEmpty().contains("Language:"))
    }

    @Test
    fun fileSizesHandleWebsiteUnitsWithoutSwallowingExceptions() {
        assertEquals(1536.0, parseHumanReadableByteCount("1.5 KiB"))
        assertEquals(1200.0, parseHumanReadableByteCount("1.2 KB"))
        assertEquals(512.0, parseHumanReadableByteCount("512 B"))
        assertNull(parseHumanReadableByteCount("Unknown"))
    }
}
