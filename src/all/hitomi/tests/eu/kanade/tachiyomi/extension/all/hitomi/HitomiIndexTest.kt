package eu.kanade.tachiyomi.extension.all.hitomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HitomiIndexTest {
    @Test
    fun rangePayloadDecodesExactlyTwentyFiveBigEndianIds() {
        val ids = IntArray(25) { 3_000_000 + it }
        val buffer = ByteBuffer.allocate(100)
        ids.forEach(buffer::putInt)
        assertArrayEquals(ids, decodeGalleryIds(buffer.array()))
        assertArrayEquals(IntArray(0), decodeGalleryIds(ByteArray(0)))
        assertThrows(IllegalArgumentException::class.java) { decodeGalleryIds(ByteArray(3)) }
    }

    @Test
    fun intersectionsPreserveRankingAndAnEmptyIntersectionStaysEmpty() {
        val base = intArrayOf(9, 4, 7, 2, 5)
        assertArrayEquals(intArrayOf(9, 7), filterGalleryIds(base, listOf(intArrayOf(7, 9, 2)), listOf(intArrayOf(2))))
        assertArrayEquals(IntArray(0), filterGalleryIds(base, listOf(intArrayOf(1), base), emptyList()))
        assertSame(base, filterGalleryIds(base, emptyList(), emptyList()))
        assertArrayEquals(intArrayOf(9, 7, 5), filterGalleryIds(base, emptyList(), listOf(intArrayOf(4, 2))))
    }

    @Test
    fun aFullLastPageDoesNotRequestAnExtraPage() {
        assertFalse(hasNextNozomiPage(0L..99L, 100, 25))
        assertTrue(hasNextNozomiPage(0L..99L, 104, 25))
        assertFalse(hasNextNozomiPage(100L..199L, 104, 1))
    }

    @Test
    fun languageAndRankingCanPageDirectlyButTagsTypesAndRandomCannot() {
        val filters = getFilters()
        assertTrue(filters.canUseNozomiPage(""))
        filters.filterIsInstance<LanguageFilter>().single().state = 25
        assertTrue(filters.canUseNozomiPage(""))
        val sort = filters.filterIsInstance<SelectFilter>().single()
        sort.state = 5
        assertTrue(filters.canUseNozomiPage(""))
        sort.state = 6
        assertFalse(filters.canUseNozomiPage(""))
        sort.state = 2
        assertFalse(filters.canUseNozomiPage("artist name"))
        val tag = filters.filterIsInstance<TextFilter>().first()
        tag.state = "-group"
        assertFalse(filters.canUseNozomiPage(""))
        tag.state = ""
        filters.filterIsInstance<TypeFilter>().single().state.first().state = false
        assertFalse(filters.canUseNozomiPage(""))
    }

    @Test
    fun paginationKeysIncludeLanguageRankingTypeAndTagChanges() {
        val filters = getFilters()
        val original = filters.searchKey("query", "all")
        assertEquals(original, getFilters().searchKey("query", "all"))
        assertNotEquals(original, filters.searchKey("query", "chinese"))
        filters.filterIsInstance<SelectFilter>().single().state = 3
        assertNotEquals(original, filters.searchKey("query", "all"))
        filters.filterIsInstance<SelectFilter>().single().state = 2
        filters.filterIsInstance<TypeFilter>().single().state.first().state = false
        assertNotEquals(original, filters.searchKey("query", "all"))
        filters.filterIsInstance<TypeFilter>().single().state.first().state = true
        filters.filterIsInstance<TextFilter>().first().state = "tag"
        assertNotEquals(original, filters.searchKey("query", "all"))
    }

    @Test
    fun primitiveFilteringMatchesThePreviousAlgorithmOnALargeIndex() {
        val base = IntArray(250_000) { (it * 53) % 4_000_003 + 1 }
        val positive = IntArray(base.size / 2) { base[it * 2] }
        val negative = IntArray(base.size / 20) { base[it * 20] }
        fun previous(): IntArray {
            val results = base.toCollection(LinkedHashSet(base.size * 2))
            results.retainAll(positive.toHashSet())
            results.removeAll(negative.toHashSet())
            return results.toIntArray()
        }
        fun current() = filterGalleryIds(base, listOf(positive), listOf(negative))
        assertArrayEquals(previous(), current())
        repeat(2) { previous(); current() }
        val oldTimes = LongArray(5)
        val newTimes = LongArray(5)
        repeat(5) {
            var start = System.nanoTime()
            previous()
            oldTimes[it] = System.nanoTime() - start
            start = System.nanoTime()
            current()
            newTimes[it] = System.nanoTime() - start
        }
        println("250000 IDs: previous median ${oldTimes.sorted()[2] / 1_000_000.0} ms; primitive median ${newTimes.sorted()[2] / 1_000_000.0} ms")
    }
}
