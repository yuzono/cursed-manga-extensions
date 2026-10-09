package eu.kanade.tachiyomi.extension.all.ehentai

import keiyoushi.utils.tryParseDateTime
import org.jsoup.nodes.Element
import java.time.ZoneOffset

internal fun Element.galleryPostedDate(): Long {
    val posted = select("#gdd tr")
        .firstOrNull { it.select(".gdt1").text() == "Posted:" }
        ?.select(".gdt2")
        ?.text()
    return EX_DATE_FORMAT.tryParseDateTime(posted, ZoneOffset.UTC)
}
