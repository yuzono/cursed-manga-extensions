package eu.kanade.tachiyomi.extension.all.ehentai

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryListTest {
    @Test
    fun listingAlreadyContainsCreatorMetadataBeforeDetailsAreLoaded() {
        val listing = parse(
            """
            <table class="itg"><tr><td><a href="/g/1/abcdef/"><div class="glink">[Title credit] Gallery</div></a>
            <div title="artist:tagged creator">tagged creator</div></td></tr>
            <tr><td><a href="/g/2/abcdef/"><div class="glink">[Toratora] Gallery [AI Generated]</div></a></td></tr></table>
            """,
        )
        assertEquals(listOf("tagged creator", "Toratora"), listing.galleries.map { it.toSManga().author })
        assertEquals(listing.galleries.map { it.toSManga().author }, listing.galleries.map { it.toSManga().artist })
        assertEquals(listOf(false, false), listing.galleries.map { it.toSManga().initialized })
    }

    @Test
    fun parsesCompactAndMinimalRowsWithLazyThumbnails() {
        val listing = parse(
            """
            <table class="itg gltc"><tr>
              <td><div class="glthumb"><img src="/placeholder.png" data-src="https://thumb.test/1.jpg"></div></td>
              <td class="gl3c glname"><a href="/g/1/abcdef/"><div class="glink">Gallery one</div></a>
                <div title="language:chinese">chinese</div>
              </td>
            </tr></table>
            """,
        )
        assertEquals("Gallery one", listing.galleries.single().title)
        assertEquals("/g/1/abcdef/?nw=always", listing.galleries.single().url)
        assertEquals("https://thumb.test/1.jpg", listing.galleries.single().thumbnailUrl)
    }

    @Test
    fun parsesExtendedRowsWhereGlnameIsNotATableCell() {
        val listing = parse(
            """
            <table class="itg glte"><tr>
              <td class="gl1e"><div><a href="/g/2/abcdef/"><img src="https://thumb.test/2.jpg"></a></div></td>
              <td class="gl2e"><div><a href="/g/2/abcdef/"><div class="gl4e glname"><div class="glink">Gallery two</div></div></a></div>
                <div title="language:english">english</div>
              </td>
            </tr></table>
            """,
        )
        assertEquals("Gallery two", listing.galleries.single().title)
        assertEquals("https://thumb.test/2.jpg", listing.galleries.single().thumbnailUrl)
    }

    @Test
    fun parsesThumbnailCardsAndResolvesRelativeImageUrls() {
        val listing = parse(
            """
            <div class="itg glt"><div class="gl1t">
              <div class="gl3t"><a href="/g/3/abcdef/"><img src="/thumb/3.jpg"></a></div>
              <div class="gl4t"><a href="/g/3/abcdef/"><div class="glink">Gallery three</div></a></div>
            </div></div>
            """,
        )
        assertEquals("Gallery three", listing.galleries.single().title)
        assertEquals("https://exhentai.org/thumb/3.jpg", listing.galleries.single().thumbnailUrl)
    }

    @Test
    fun listsAllLanguagesAndKeepsTheWebsiteCursor() {
        val listing = parse(
            """
            <table class="itg">
              ${row(10, "chinese")}
              ${row(9, null)}
              ${row(8, "english")}
            </table>
            <a id="unext" href="/watched?next=7">Next &gt;</a>
            """,
        )
        assertEquals(listOf("Gallery 10", "Gallery 9", "Gallery 8"), listing.galleries.map { it.title })
        assertEquals("https://exhentai.org/watched?next=7", listing.nextPageUrl)
    }

    @Test
    fun finitePopularListAndDisabledNextLinkDoNotPaginate() {
        assertNull(parse("<table class='itg'>${row(1, null)}</table>").nextPageUrl)
        assertNull(parse("<p>No hits found</p><a id='unext'>Next &gt;</a>").nextPageUrl)
        assertNull(parse("<p>No hits found</p><a id='unext' href=''>Next &gt;</a>").nextPageUrl)
    }

    @Test
    fun galleryListFilterChoosesExactlyOneWebsitePath() {
        val filter = GalleryListFilter()
        assertEquals("", filter.path)
        filter.state = 1
        assertEquals("watched", filter.path)
        filter.state = 2
        assertEquals("popular", filter.path)
        filter.state = 3
        assertEquals("favorites.php", filter.path)
    }

    @Test
    fun languageSelectionUsesWebsiteTagsAndKeepsTheSearchQuery() {
        val filter = GalleryLanguageFilter()
        assertEquals("", filter.addToQuery(""))
        assertEquals("artist:example", filter.addToQuery("artist:example"))
        filter.state = filter.values.indexOf("中文")
        assertEquals("language:chinese", filter.addToQuery(""))
        assertEquals("artist:example language:chinese", filter.addToQuery("artist:example"))
        filter.state = filter.values.indexOf("日语")
        assertEquals("language:japanese", filter.addToQuery(""))
        filter.state = filter.values.indexOf("韩语")
        assertEquals("language:korean", filter.addToQuery(""))
        filter.state = filter.values.indexOf("英语")
        assertEquals("language:english", filter.addToQuery(""))
    }

    private fun parse(html: String) = GalleryList(Jsoup.parse(html, "https://exhentai.org/watched"))

    private fun row(id: Int, language: String?) = """
        <tr><td class="glname"><a href="/g/$id/abcdef/"><div class="glink">Gallery $id</div></a>
        ${language?.let { "<div title='language:$it'>$it</div>" }.orEmpty()}</td></tr>
    """
}
