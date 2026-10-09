package eu.kanade.tachiyomi.extension.all.ehentai

import org.jsoup.nodes.Document
import rx.Observable
import java.util.concurrent.TimeUnit

internal enum class GalleryPageUse { DETAILS, CHAPTERS, READER, IMAGE_PAGES }

internal class GalleryPageLoader(private val nanoTime: () -> Long = System::nanoTime) {
    private class Entry(use: GalleryPageUse) {
        val uses = mutableSetOf(use)
        var document: Document? = null
        var loadedAt = 0L
        lateinit var request: Observable<Document>

        fun finishedOpening() = GalleryPageUse.DETAILS in uses && GalleryPageUse.CHAPTERS in uses && GalleryPageUse.READER in uses
    }

    private val entries = object : LinkedHashMap<String, Entry>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 4
    }

    fun load(key: String, use: GalleryPageUse, fetch: () -> Observable<Document>): Observable<Document> = Observable.defer {
        synchronized(this) {
            val now = nanoTime()
            entries.entries.removeAll { (_, entry) ->
                // Image-page links remain valid while reading; only opening metadata expires by time.
                entry.document != null && GalleryPageUse.IMAGE_PAGES !in entry.uses && now - entry.loadedAt >= TimeUnit.MINUTES.toNanos(1)
            }
            val cached = entries[key]
            // Each stage of opening a gallery can reuse the page once. Repeating a stage is a refresh.
            if (cached != null && (cached.document == null || use == GalleryPageUse.IMAGE_PAGES || use !in cached.uses)) {
                cached.uses += use
                val document = cached.document
                if (document != null) {
                    if (cached.finishedOpening()) entries.remove(key)
                    Observable.just(document)
                } else {
                    cached.request
                }
            } else {
                val entry = Entry(use)
                entry.request = Observable.defer(fetch)
                    .doOnNext { document ->
                        synchronized(this) {
                            entry.document = document
                            entry.loadedAt = nanoTime()
                            if (entry.finishedOpening() && entries[key] === entry) entries.remove(key)
                        }
                    }
                    .doOnUnsubscribe {
                        synchronized(this) {
                            if (entry.document == null && entries[key] === entry) entries.remove(key)
                        }
                    }
                    .replay(1)
                    .refCount()
                entries[key] = entry
                entry.request
            }
        }
    }
}
