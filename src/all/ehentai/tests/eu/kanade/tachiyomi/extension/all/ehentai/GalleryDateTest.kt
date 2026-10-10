package eu.kanade.tachiyomi.extension.all.ehentai

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class GalleryDateTest {
    @Test
    fun chapterUsesThePostedRowInUtcRegardlessOfTheDeviceTimeZone() {
        val request = Request.Builder().url("https://exhentai.org/g/1/abcdef/").build()
        val html =
            """
            <div id="gdd"><table>
              <tr><td class="gdt1">Parent:</td><td class="gdt2">123</td></tr>
              <tr><td class="gdt1">Posted:</td><td class="gdt2">2026-10-07 13:09</td></tr>
              <tr><td class="gdt1">Length:</td><td class="gdt2">744 pages</td></tr>
            </table></div>
            """
        val originalZone = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Shanghai", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val chapter = htmlResponse(request, html).use {
                    TestSource().chapterListParse(it).single()
                }
                assertEquals(1791378540000L, chapter.date_upload)
                val posted = EX_DATE_FORMAT.format(Instant.ofEpochMilli(chapter.date_upload))
                assertEquals("2026-10-07 13:09", posted)
                assertEquals("/g/1/abcdef/?nw=always", chapter.url)
            }
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }
}
