package eu.kanade.tachiyomi.extension.all.hitomi

import java.nio.ByteBuffer
import java.util.BitSet

internal fun decodeGalleryIds(bytes: ByteArray): IntArray {
    require(bytes.size % Int.SIZE_BYTES == 0) { "Invalid gallery index length" }
    val buffer = ByteBuffer.wrap(bytes)
    return IntArray(bytes.size / Int.SIZE_BYTES) { buffer.int }
}

internal fun hasNextNozomiPage(range: LongRange, total: Long?, count: Int) = if (total != null) range.last + 1 < total else count == 25

internal fun filterGalleryIds(base: IntArray, positive: List<IntArray>, negative: List<IntArray>): IntArray {
    if (base.isEmpty()) return base
    if (positive.isEmpty() && negative.isEmpty()) return base

    var matches: BitSet? = null
    positive.forEach { ids ->
        val filter = BitSet()
        ids.forEach(filter::set)
        if (matches == null) {
            matches = filter
        } else {
            matches.and(filter)
        }
    }
    val included = matches ?: BitSet().apply { base.forEach(::set) }
    negative.forEach { ids -> ids.forEach(included::clear) }
    if (included.isEmpty) return IntArray(0)

    val result = IntArray(base.size)
    var size = 0
    base.forEach { id -> if (included[id]) result[size++] = id }
    return if (size == base.size) base else result.copyOf(size)
}
