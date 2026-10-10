package eu.kanade.tachiyomi.extension.all.hitomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable
import rx.observers.TestSubscriber
import rx.subjects.PublishSubject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HitomiReaderTest {
    private val gallery = Gallery("/manga/test-1.html", "Test", null, "2026-10-10 00:00:00-00", "manga", "chinese", null, null, null, null, null, emptyList())

    @Test
    fun concurrentOpeningStagesShareOneFetchAndReleaseMetadataAfterReading() {
        val cache = GalleryOpeningCache()
        val response = PublishSubject.create<Gallery>()
        var requests = 0
        val details = TestSubscriber<Gallery>()
        val chapters = TestSubscriber<Gallery>()
        cache.load("1", GalleryUse.DETAILS) { requests++; response }.subscribe(details)
        cache.load("1", GalleryUse.CHAPTERS) { error("Duplicate request") }.subscribe(chapters)
        response.onNext(gallery)
        response.onCompleted()
        details.assertValue(gallery)
        chapters.assertValue(gallery)
        assertSame(gallery, cache.load("1", GalleryUse.READER) { error("Duplicate request") }.toBlocking().single())
        assertEquals(1, requests)
        cache.load("1", GalleryUse.DETAILS) { requests++; Observable.just(gallery) }.toBlocking().single()
        assertEquals(2, requests)
    }

    @Test
    fun refreshExpiryAndEvictionFetchFreshMetadata() {
        var now = 0L
        val cache = GalleryOpeningCache { now }
        var requests = 0
        fun load(id: String, use: GalleryUse) = cache.load(id, use) { requests++; Observable.just(gallery) }.toBlocking().single()
        load("1", GalleryUse.DETAILS)
        load("1", GalleryUse.DETAILS)
        assertEquals(2, requests)
        now = TimeUnit.MINUTES.toNanos(1)
        load("1", GalleryUse.CHAPTERS)
        assertEquals(3, requests)
        (2..5).forEach { load(it.toString(), GalleryUse.DETAILS) }
        load("1", GalleryUse.READER)
        assertEquals(8, requests)
    }

    @Test
    fun aFailedOpeningRequestCanBeRetried() {
        val cache = GalleryOpeningCache()
        assertThrows(RuntimeException::class.java) {
            cache.load("1", GalleryUse.DETAILS) { Observable.error(IOException("HTTP 503")) }.toBlocking().single()
        }
        assertSame(gallery, cache.load("1", GalleryUse.CHAPTERS) { Observable.just(gallery) }.toBlocking().single())
    }

    @Test
    fun cancellingTheLastCallerCancelsTheRequestAndAllowsRetry() {
        val cache = GalleryOpeningCache()
        val response = PublishSubject.create<Gallery>()
        var cancellations = 0
        val first = TestSubscriber<Gallery>()
        val second = TestSubscriber<Gallery>()
        cache.load("1", GalleryUse.DETAILS) { response.doOnUnsubscribe { cancellations++ } }.subscribe(first)
        cache.load("1", GalleryUse.CHAPTERS) { error("Duplicate request") }.subscribe(second)
        first.unsubscribe()
        assertEquals(0, cancellations)
        second.unsubscribe()
        assertEquals(1, cancellations)
        assertSame(gallery, cache.load("1", GalleryUse.READER) { Observable.just(gallery) }.toBlocking().single())
    }

    @Test
    fun oneRoutingSnapshotResolvesNormalGifAndThumbnailUrls() {
        val routing = parseImageRouting("var o = 0; case 3243: o = 1; break; b: '123/'", 0)
        val hash = "0123456789abcdefabc"
        assertEquals("https://a2.cdn.test/123/3243/$hash.avif", routing.imageUrl(hash, false, false, "cdn.test"))
        assertEquals("https://w2.cdn.test/123/3243/$hash.webp", routing.imageUrl(hash, true, false, "cdn.test"))
        assertEquals("https://btn.cdn.test/avifbigtn/c/ab/$hash.avif", routing.imageUrl(hash, false, true, "cdn.test"))
        val defaultHash = "0123456789abcdef000"
        assertEquals("https://a1.cdn.test/123/0/$defaultHash.avif", routing.imageUrl(defaultHash, false, false, "cdn.test"))
        assertTrue(routing.isFresh(TimeUnit.SECONDS.toNanos(59)))
        assertFalse(routing.isFresh(TimeUnit.MINUTES.toNanos(1)))
    }
}
