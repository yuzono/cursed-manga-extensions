package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.observers.TestSubscriber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GalleryReaderTest {
    @Test
    fun galleryIdSearchReusesItsDetailsPageForTheChapterAndReader() {
        GalleryTestServer(imageCount = 2).use { server ->
            listOf("id:1/token", "id:1/token/", "https://exhentai.org/g/1/token/").forEach { query ->
                val source = TestSource(server.baseUrl)
                val manga = source.fetchSearchManga(1, query, FilterList()).toBlocking().single().mangas.single()
                assertTrue(manga.initialized)
                val chapter = source.fetchChapterList(manga).toBlocking().single().single()
                source.fetchPageList(chapter).toBlocking().single()
            }
            assertEquals(listOf(0, 0, 0), server.directories)
        }
    }

    @Test
    fun detailsChaptersAndReaderReuseTheSameDirectoryPage() {
        GalleryTestServer().use { server ->
            val source = TestSource(server.baseUrl)
            val manga = SManga.create().apply { url = "/g/1/token/" }
            val details = source.fetchMangaDetails(manga).toBlocking().single()
            val chapter = source.fetchChapterList(manga).toBlocking().single().single()
            val pages = source.fetchPageList(chapter).toBlocking().single()
            assertTrue(details.initialized)
            assertEquals("Gallery", details.title)
            assertEquals(1791378540000L, chapter.date_upload)
            assertEquals(10, pages.size)
            assertEquals(listOf(0), server.directories)
        }
    }

    @Test
    fun overlappingDetailsAndChaptersShareTheRequestWhenOneCallerCancels() {
        val gate = CountDownLatch(1)
        GalleryTestServer(firstPageGate = gate).use { server ->
            val source = TestSource(server.baseUrl)
            val manga = SManga.create().apply { url = "/g/1/token/" }
            val details = TestSubscriber<SManga>()
            val chapters = TestSubscriber<List<SChapter>>()
            try {
                source.fetchMangaDetails(manga).subscribe(details)
                assertTrue(server.firstPageStarted.await(5, TimeUnit.SECONDS))
                source.fetchChapterList(manga).subscribe(chapters)
                details.unsubscribe()
            } finally {
                gate.countDown()
            }
            chapters.awaitTerminalEvent(5, TimeUnit.SECONDS)
            chapters.assertNoErrors()
            chapters.assertCompleted()
            details.assertNoValues()
            source.fetchPageList(chapters.onNextEvents.single().single()).toBlocking().single()
            assertEquals(listOf(0), server.directories)
        }
    }

    @Test
    fun refreshingChaptersFetchesFreshDataInsteadOfReusingThePreviousOpening() {
        GalleryTestServer().use { server ->
            val source = TestSource(server.baseUrl)
            val manga = SManga.create().apply { url = "/g/1/token/" }
            source.fetchMangaDetails(manga).toBlocking().single()
            source.fetchChapterList(manga).toBlocking().single()
            source.fetchChapterList(manga).toBlocking().single()
            assertEquals(listOf(0, 0), server.directories)
        }
    }

    @Test
    fun firstImageDoesNotWaitForOrRequestAnyLaterDirectory() {
        val gate = CountDownLatch(1)
        GalleryTestServer(imageCount = 5000, gate = gate).use { server ->
            try {
                val source = TestSource(server.baseUrl)
                val pages = source.fetchPageList(chapter()).timeout(3, TimeUnit.SECONDS).toBlocking().single()
                assertEquals((0 until 5000).toList(), pages.map { it.index })
                assertEquals("${server.baseUrl}/images/1.jpg", source.fetchImageUrl(pages.first()).toBlocking().single())
                assertEquals(listOf(0), server.directories)
                assertEquals(listOf(1), server.imagePages)
                assertEquals(1L, gate.count)
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun jumpingToTheLastPageAndReadingBackOnlyLoadsThatDirectory() {
        GalleryTestServer(imageCount = 9).use { server ->
            val source = TestSource(server.baseUrl)
            val pages = source.fetchPageList(chapter()).toBlocking().single()
            assertEquals("${server.baseUrl}/images/9.jpg", source.fetchImageUrl(pages.last()).toBlocking().single())
            assertEquals(listOf(0, 4), server.directories)
            assertEquals("${server.baseUrl}/images/8.jpg", source.fetchImageUrl(pages[7]).toBlocking().single())
            assertEquals("${server.baseUrl}/images/7.jpg", source.fetchImageUrl(pages[6]).toBlocking().single())
            assertEquals(listOf(0, 4, 3), server.directories)
            assertEquals(listOf(9, 8, 7), server.imagePages)
            assertTrue(server.warningParameters.all { it == "always" })
        }
    }

    @Test
    fun sequentialReadingResolvesEveryImageOnceIncludingTheLastPartialDirectory() {
        listOf(1, 2, 9, 42).forEach { count ->
            GalleryTestServer(imageCount = count, perDirectory = 7).use { server ->
                val source = TestSource(server.baseUrl)
                val pages = source.fetchPageList(chapter()).toBlocking().single()
                assertEquals((1..count).map { "${server.baseUrl}/images/$it.jpg" }, pages.map { source.fetchImageUrl(it).toBlocking().single() })
                assertEquals((0..(count - 1) / 7).toList(), server.directories)
                assertEquals((1..count).toList(), server.imagePages)
            }
        }
    }

    @Test
    fun persistedPageLocatorsWorkWithANewSourceInstance() {
        GalleryTestServer().use { server ->
            val stored = TestSource(server.baseUrl).fetchPageList(chapter()).toBlocking().single()[7]
            val restored = Page(stored.index, stored.url)
            assertEquals("${server.baseUrl}/images/8.jpg", TestSource(server.baseUrl).fetchImageUrl(restored).toBlocking().single())
            assertEquals(listOf(0, 3), server.directories)
        }
    }

    @Test
    fun concurrentImagesShareTheirDirectoryEvenWhenOneCallerCancels() {
        val gate = CountDownLatch(1)
        GalleryTestServer(gate = gate).use { server ->
            val source = TestSource(server.baseUrl)
            val pages = source.fetchPageList(chapter()).toBlocking().single()
            val first = TestSubscriber<String>()
            val second = TestSubscriber<String>()
            try {
                source.fetchImageUrl(pages[4]).subscribe(first)
                assertTrue(server.pendingStarted.await(5, TimeUnit.SECONDS))
                source.fetchImageUrl(pages[5]).subscribe(second)
                first.unsubscribe()
            } finally {
                gate.countDown()
            }
            second.awaitTerminalEvent(5, TimeUnit.SECONDS)
            second.assertNoErrors()
            second.assertCompleted()
            second.assertValue("${server.baseUrl}/images/6.jpg")
            first.assertNoValues()
            assertEquals(listOf(0, 2), server.directories)
            assertEquals(listOf(6), server.imagePages)
        }
    }

    @Test
    fun laterDirectoryFailuresAreIsolatedAndCanBeRetried() {
        GalleryTestServer().use { server ->
            server.failurePage.set(2)
            val source = TestSource(server.baseUrl)
            val pages = source.fetchPageList(chapter()).toBlocking().single()
            assertEquals("${server.baseUrl}/images/1.jpg", source.fetchImageUrl(pages.first()).toBlocking().single())
            val error = assertThrows(RuntimeException::class.java) { source.fetchImageUrl(pages[4]).toBlocking().single() }
            assertEquals("HTTP 503", error.cause?.message)
            server.failurePage.set(-1)
            assertEquals("${server.baseUrl}/images/5.jpg", source.fetchImageUrl(pages[4]).toBlocking().single())
            assertEquals(listOf(0, 2, 2), server.directories)
        }
    }

    @Test
    fun invalidHtmlDoesNotPoisonTheDirectoryCache() {
        GalleryTestServer().use { server ->
            val source = TestSource(server.baseUrl)
            val pages = source.fetchPageList(chapter()).toBlocking().single()
            server.invalidPage.set(2)
            assertThrows(IllegalStateException::class.java) { source.fetchImageUrl(pages[4]).toBlocking().single() }
            server.invalidPage.set(-1)
            assertEquals("${server.baseUrl}/images/5.jpg", source.fetchImageUrl(pages[4]).toBlocking().single())
            assertEquals(listOf(0, 2, 2), server.directories)
        }
    }

    @Test
    fun cancellingTheLastImageCallerStopsItsDirectoryWithoutStartingMoreWork() {
        val gate = CountDownLatch(1)
        GalleryTestServer(gate = gate).use { server ->
            val source = TestSource(server.baseUrl)
            val page = source.fetchPageList(chapter()).toBlocking().single()[4]
            val subscriber = TestSubscriber<String>()
            try {
                source.fetchImageUrl(page).subscribe(subscriber)
                assertTrue(server.pendingStarted.await(5, TimeUnit.SECONDS))
                subscriber.unsubscribe()
            } finally {
                gate.countDown()
            }
            assertTrue(server.pendingFinished.await(5, TimeUnit.SECONDS))
            subscriber.assertNoValues()
            subscriber.assertNoErrors()
            assertTrue(server.imagePages.isEmpty())
            assertEquals(listOf(0, 2), server.directories)
            assertEquals("${server.baseUrl}/images/5.jpg", source.fetchImageUrl(page).toBlocking().single())
            assertEquals(listOf(0, 2, 2), server.directories)
        }
    }

    private fun chapter() = SChapter.create().apply { url = "/g/1/token/?nw=always" }
}
