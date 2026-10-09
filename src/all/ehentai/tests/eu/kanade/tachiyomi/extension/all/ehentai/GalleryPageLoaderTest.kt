package eu.kanade.tachiyomi.extension.all.ehentai

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import rx.Observable
import rx.observers.TestSubscriber
import rx.subjects.PublishSubject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GalleryPageLoaderTest {
    private var now = 0L
    private val loader = GalleryPageLoader { now }
    private var requests = 0

    @Test
    fun allOpeningStagesShareOnePageThenReleaseIt() {
        val details = load("gallery", GalleryPageUse.DETAILS)
        assertSame(details, load("gallery", GalleryPageUse.CHAPTERS))
        assertSame(details, load("gallery", GalleryPageUse.READER))
        assertEquals(1, requests)
        load("gallery", GalleryPageUse.READER)
        assertEquals(2, requests)
    }

    @Test
    fun repeatedStageAndExpiredPageBothFetchFreshData() {
        load("gallery", GalleryPageUse.DETAILS)
        load("gallery", GalleryPageUse.DETAILS)
        assertEquals(2, requests)
        now += TimeUnit.MINUTES.toNanos(1)
        load("gallery", GalleryPageUse.CHAPTERS)
        assertEquals(3, requests)
    }

    @Test
    fun retainingDifferentGalleryPagesIsBoundedAndRecentGalleriesSurvive() {
        val active = load("active", GalleryPageUse.DETAILS)
        load("old", GalleryPageUse.DETAILS)
        load("third", GalleryPageUse.DETAILS)
        load("fourth", GalleryPageUse.DETAILS)
        assertSame(active, load("active", GalleryPageUse.CHAPTERS))
        load("fifth", GalleryPageUse.DETAILS)
        assertSame(active, load("active", GalleryPageUse.READER))
        load("old", GalleryPageUse.CHAPTERS)
        assertEquals(6, requests)
    }

    @Test
    fun failedRequestIsNotRetainedForTheNextAttempt() {
        assertThrows(RuntimeException::class.java) {
            loader.load("gallery", GalleryPageUse.DETAILS) { Observable.error(IOException("HTTP 503")) }
                .toBlocking().single()
        }
        load("gallery", GalleryPageUse.CHAPTERS)
        assertEquals(1, requests)
    }

    @Test
    fun cancellingTheLastCallerReleasesTheRequestAndAllowsRetry() {
        val response = PublishSubject.create<Document>()
        var cancellations = 0
        val first = TestSubscriber<Document>()
        val second = TestSubscriber<Document>()
        val fetch = { response.doOnUnsubscribe { cancellations++ } }
        loader.load("gallery", GalleryPageUse.DETAILS, fetch).subscribe(first)
        loader.load("gallery", GalleryPageUse.CHAPTERS, fetch).subscribe(second)
        first.unsubscribe()
        assertEquals(0, cancellations)
        second.unsubscribe()
        assertEquals(1, cancellations)
        load("gallery", GalleryPageUse.READER)
        assertEquals(1, requests)
    }

    @Test
    fun differentGalleryHostsDoNotSharePages() {
        load("https://e-hentai.org/g/1/token/", GalleryPageUse.DETAILS)
        load("https://exhentai.org/g/1/token/", GalleryPageUse.CHAPTERS)
        assertEquals(2, requests)
    }

    @Test
    fun adjacentImageRequestsReuseTheirDirectoryEvenWhenReadingSlowly() {
        val directory = load("gallery?p=1", GalleryPageUse.IMAGE_PAGES)
        repeat(40) {
            now += TimeUnit.MINUTES.toNanos(1)
            assertSame(directory, load("gallery?p=1", GalleryPageUse.IMAGE_PAGES))
        }
        assertEquals(1, requests)
    }

    @Test
    fun imageDirectoryRetentionIsBoundedDuringLongReadingSessions() {
        repeat(1000) { load("gallery?p=$it", GalleryPageUse.IMAGE_PAGES) }
        load("gallery?p=999", GalleryPageUse.IMAGE_PAGES)
        assertEquals(1000, requests)
        load("gallery?p=995", GalleryPageUse.IMAGE_PAGES)
        assertEquals(1001, requests)
    }

    private fun load(key: String, use: GalleryPageUse): Document = loader.load(key, use) {
        requests++
        Observable.just(Jsoup.parse("<div id='gdt'></div>", "https://e-hentai.org/g/1/token/"))
    }.toBlocking().single()
}
