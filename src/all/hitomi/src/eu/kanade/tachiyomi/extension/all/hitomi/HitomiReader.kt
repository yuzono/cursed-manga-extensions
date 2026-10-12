package eu.kanade.tachiyomi.extension.all.hitomi

import rx.Observable
import java.util.concurrent.TimeUnit

internal enum class GalleryUse { DETAILS, CHAPTERS, READER }

internal class GalleryOpeningCache(private val nanoTime: () -> Long = System::nanoTime) {
    private class Entry(use: GalleryUse) {
        val uses = mutableSetOf(use)
        var gallery: Gallery? = null
        var fetchedAt = 0L
        lateinit var request: Observable<Gallery>
    }

    private val entries = object : LinkedHashMap<String, Entry>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 4
    }

    fun load(id: String, use: GalleryUse, fetch: () -> Observable<Gallery>): Observable<Gallery> = Observable.defer {
        synchronized(entries) {
            val now = nanoTime()
            entries.entries.removeAll { (_, entry) -> entry.gallery != null && now - entry.fetchedAt >= TimeUnit.MINUTES.toNanos(1) }
            val cached = entries[id]
            if (cached != null && (cached.gallery == null || use !in cached.uses)) {
                cached.uses += use
                val gallery = cached.gallery
                if (gallery != null) {
                    if (use == GalleryUse.READER) entries.remove(id)
                    Observable.just(gallery)
                } else {
                    cached.request
                }
            } else {
                val entry = Entry(use)
                entry.request = Observable.defer(fetch)
                    .doOnNext { gallery ->
                        synchronized(entries) {
                            entry.gallery = gallery
                            entry.fetchedAt = nanoTime()
                            // Only retain metadata while opening; the reader now owns the page list.
                            if (GalleryUse.READER in entry.uses && entries[id] === entry) entries.remove(id)
                        }
                    }
                    .doOnUnsubscribe {
                        synchronized(entries) { if (entry.gallery == null && entries[id] === entry) entries.remove(id) }
                    }
                    .replay(1)
                    .refCount()
                entries[id] = entry
                entry.request
            }
        }
    }
}

internal class ImageRouting(
    private val fetchedAt: Long,
    private val offsets: ByteArray,
    private val path: String,
) {
    fun isFresh(now: Long) = now - fetchedAt < TimeUnit.MINUTES.toNanos(1)

    fun imageUrl(hash: String, isGif: Boolean, isThumbnail: Boolean, cdnDomain: String): String {
        val end = hash.lastIndex
        val imageId = (hash[end].digitToInt(16) shl 8) or (hash[end - 2].digitToInt(16) shl 4) or hash[end - 1].digitToInt(16)
        val offset = offsets[imageId].toInt()
        val type = if (isGif) "webp" else "avif"
        return if (isThumbnail) {
            "https://${'a' + offset}tn.$cdnDomain/${type}bigtn/${hash[end]}/${hash[end - 2]}${hash[end - 1]}/$hash.$type"
        } else {
            val subdomain = if (isGif) "w${offset + 1}" else "a${offset + 1}"
            "https://$subdomain.$cdnDomain/$path$imageId/$hash.$type"
        }
    }
}

private val defaultOffsetPattern = Regex("var o = (\\d)")
private val switchOffsetPattern = Regex("o = (\\d); break;")
private val offsetCasesPattern = Regex("case (\\d+):")
private val imagePathPattern = Regex("b: '(.+)'")

internal fun parseImageRouting(script: String, nanoTime: Long): ImageRouting {
    val default = defaultOffsetPattern.find(script)!!.groupValues[1].toInt()
    val offset = switchOffsetPattern.find(script)!!.groupValues[1].toInt()
    val offsets = ByteArray(1 shl 12) { default.toByte() }
    offsetCasesPattern.findAll(script).forEach { offsets[it.groupValues[1].toInt()] = offset.toByte() }
    return ImageRouting(nanoTime, offsets, imagePathPattern.find(script)!!.groupValues[1])
}
