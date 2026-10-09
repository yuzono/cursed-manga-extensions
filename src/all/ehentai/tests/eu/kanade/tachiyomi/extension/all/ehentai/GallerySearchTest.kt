package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GallerySearchTest {
    private val source = TestSource()

    @Test
    fun popularIsAvailableInTheFilterListWithoutASyntheticSearchOrPagination() {
        val filters = source.getFilterList()
        val list = filters.filterIsInstance<GalleryListFilter>().single()
        list.state = list.values.indexOf("Popular")
        assertEquals(2, list.state)
        assertEquals("https://exhentai.org/popular", source.searchMangaRequest(1, "", filters).url.toString())
    }

    @Test
    fun watchedLanguageAndCategoryFiltersAreIncludedInTheActualRequest() {
        val filters = filters(language = "中文", category = "Manga")
        val url = source.searchMangaRequest(1, "", filters).url
        assertEquals("/watched", url.encodedPath)
        assertEquals("language:chinese", url.queryParameter("f_search"))
        assertEquals("1019", url.queryParameter("f_cats"))
    }

    @Test
    fun untouchedFiltersKeepWebsiteDefaultsIncludingTagBlocking() {
        val filters = filters()
        val genres = filters.filterIsInstance<EHentai.GenreGroup>().single().state
        val url = source.searchMangaRequest(1, "", filters).url
        assertEquals("https://exhentai.org/watched", url.toString())
        assertNull(url.queryParameter("f_sft"))
        assertFalse(genres.any { it.state })
        assertEquals(url, source.searchMangaRequest(1, "", filters).url)
        assertFalse(genres.any { it.state })
    }

    @Test
    fun tagQueriesStayEncodedWhilePagesAndRatingApplyToWatched() {
        val filters = FilterList(
            *(
                filters(language = "英语") + listOf(
                    EHentai.TextFilter("Tags", "tag").apply { state = "full color, -comic" },
                    EHentai.MinPagesOption().apply { state = "50" },
                    EHentai.MaxPagesOption().apply { state = "100" },
                    EHentai.RatingOption().apply { state = 3 },
                )
                ).toTypedArray(),
        )
        val url = source.searchMangaRequest(1, "artist:example", filters).url
        assertEquals("artist:example language:english tag:\"full color\" -tag:\"comic\"", url.queryParameter("f_search"))
        assertNull(url.queryParameter("f_sp"))
        assertEquals("50", url.queryParameter("f_spf"))
        assertEquals("100", url.queryParameter("f_spt"))
        assertEquals("4", url.queryParameter("f_srdd"))
    }

    @Test
    fun selectingAndClearingACategoryAfterSearchingDoesNotChangeOtherCheckboxes() {
        val filters = filters()
        val genres = filters.filterIsInstance<EHentai.GenreGroup>().single().state
        source.searchMangaRequest(1, "", filters)
        val manga = genres.single { it.name == "Manga" }
        manga.state = true
        val selected = source.searchMangaRequest(1, "", filters).url
        assertEquals("1019", selected.queryParameter("f_cats"))
        assertEquals(listOf("Manga"), genres.filter { it.state }.map { it.name })

        manga.state = false
        val cleared = source.searchMangaRequest(1, "", filters).url
        assertNull(cleared.queryParameter("f_cats"))
        assertFalse(genres.any { it.state })
    }

    private fun filters(language: String? = null, category: String? = null) = FilterList(
        GalleryListFilter().apply { state = 1 },
        GalleryLanguageFilter().apply { language?.let { state = values.indexOf(it) } },
        EHentai.GenreGroup().apply { state.forEach { it.state = it.name == category } },
        EHentai.AdvancedGroup(),
    )
}
