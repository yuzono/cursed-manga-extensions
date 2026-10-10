package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.SManga
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val EH_ARTIST_NAMESPACE = "artist"

private val TITLE_CREDIT = Regex("^(?:\\([^)]*\\)\\s*)?\\[([^\\[\\]]+)]\\s+\\S")
private val CIRCLE_ARTIST = Regex("^.+?\\s*\\(([^()]+)\\)$")
private val NON_CREATOR_CREDITS = setOf("anthology", "various", "various artists", "unknown", "ai generated")

internal fun galleryCreator(title: String, artists: List<String>): String? {
    if (artists.isNotEmpty()) return artists.joinToString()
    // EH titles use [Artist] or (Convention) [Circle (Artist)] when creator tags are missing.
    val credit = TITLE_CREDIT.find(title)?.groupValues?.get(1)?.trim() ?: return null
    if (credit.lowercase() in NON_CREATOR_CREDITS) return null
    return CIRCLE_ARTIST.matchEntire(credit)?.groupValues?.get(1) ?: credit
}

private val ONGOING_SUFFIX = arrayOf(
    "[ongoing]",
    "(ongoing)",
    "{ongoing}",
)

val EX_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    .withZone(ZoneOffset.UTC)

fun ExGalleryMetadata.copyTo(manga: SManga) {
    url?.let { manga.url = it }
    thumbnailUrl?.let { manga.thumbnail_url = it }

    (title ?: altTitle)?.let { manga.title = it }

    val creator = galleryCreator(manga.title, tags[EH_ARTIST_NAMESPACE].orEmpty().map(Tag::name))
    manga.artist = creator
    manga.author = creator

    // Build genre from all tag namespaces
    // Format: "namespace:tagname" so each chip is distinct and clickable as a valid EH search query.
    val tagGenres = tags
        .flatMap { (namespace, tagList) ->
            tagList.map { tag -> "$namespace:${tag.name}" }
        }
        .sorted()

    manga.genre = tagGenres.joinToString().takeIf(String::isNotBlank)

    // Try to automatically identify if it is ongoing, we try not to be too lenient here to avoid making mistakes
    // We default to completed
    manga.status = SManga.COMPLETED
    title?.let { t ->
        if (ONGOING_SUFFIX.any {
                t.endsWith(it, ignoreCase = true)
            }
        ) {
            manga.status = SManga.ONGOING
        }
    }

    // Build a nice looking description out of what we know
    val titleDesc = StringBuilder()
    title?.let { titleDesc += "Title: $it\n" }
    altTitle?.let { titleDesc += "Alternate Title: $it\n" }

    val detailsDesc = StringBuilder()
    uploader?.let { detailsDesc += "Uploader: $it\n" }
    datePosted?.let { detailsDesc += "Posted: ${EX_DATE_FORMAT.format(Instant.ofEpochMilli(it))}\n" }
    visible?.let { detailsDesc += "Visible: $it\n" }
    category?.let { detailsDesc += "Category: $it\n" }
    language?.let {
        detailsDesc += "Language: $it"
        if (translated == true) detailsDesc += " TR"
        detailsDesc += "\n"
    }
    size?.let { detailsDesc += "File Size: ${humanReadableByteCount(it, true)}\n" }
    length?.let { detailsDesc += "Length: $it pages\n" }
    favorites?.let { detailsDesc += "Favorited: $it times\n" }
    averageRating?.let {
        detailsDesc += "Rating: $it"
        ratingCount?.let { count -> detailsDesc += " ($count)" }
        detailsDesc += "\n"
    }

    manga.description = listOf(titleDesc.toString(), detailsDesc.toString())
        .filter(String::isNotBlank)
        .joinToString(separator = "\n")
}
