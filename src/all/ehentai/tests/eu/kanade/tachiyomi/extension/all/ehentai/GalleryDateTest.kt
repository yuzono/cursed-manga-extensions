package eu.kanade.tachiyomi.extension.all.ehentai

import org.jsoup.Jsoup
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class GalleryDateTest {
    @Test
    fun readsTheWebsitePostedRowInsteadOfTheVisitDate() {
        val document = Jsoup.parse(
            """
            <div id="gdd"><table>
              <tr><td class="gdt1">Parent:</td><td class="gdt2">123</td></tr>
              <tr><td class="gdt1">Posted:</td><td class="gdt2">2026-10-07 13:09</td></tr>
              <tr><td class="gdt1">Length:</td><td class="gdt2">744 pages</td></tr>
            </table></div>
            """,
        )
        assertEquals(1791378540000L, document.galleryPostedDate())
    }

    @Test
    fun postedTimeIsUtcRegardlessOfTheDeviceTimeZone() {
        val document = Jsoup.parse(
            """
            <div id="gdd"><table><tr>
              <td class="gdt1">Posted:</td><td class="gdt2">2024-01-01 23:30</td>
            </tr></table></div>
            """,
        )
        val originalZone = TimeZone.getDefault()
        try {
            val expected = Instant.parse("2024-01-01T23:30:00Z").toEpochMilli()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals(expected, document.galleryPostedDate())
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(expected, document.galleryPostedDate())
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun chapterParserAssignsTheWebsiteTimeToTheReturnedChapter() {
        val posted = "2026-10-07 13:09"
        val request = Request.Builder().url("https://exhentai.org/g/1/abcdef/").build()
        val response = htmlResponse(
            request,
            """
            <div id="gdd"><table><tr>
              <td class="gdt1">Posted:</td><td class="gdt2">$posted</td>
            </tr></table></div>
            """,
        )
        val chapter = response.use { TestSource().chapterListParse(it).single() }
        assertEquals(1791378540000L, chapter.date_upload)
        assertEquals(posted, EX_DATE_FORMAT.format(Instant.ofEpochMilli(chapter.date_upload)))
        assertEquals("/g/1/abcdef/?nw=always", chapter.url)
    }
}
