package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.SManga
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val EH_ARTIST_NAMESPACE = "artist"
private const val EH_AUTHOR_NAMESPACE = "author"

private val ONGOING_SUFFIX = arrayOf(
    "[ongoing]",
    "(ongoing)",
    "{ongoing}",
)

val EX_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

fun ExGalleryMetadata.copyTo(manga: SManga) {
    url?.let { manga.url = it }
    thumbnailUrl?.let { manga.thumbnail_url = it }

    (title ?: altTitle)?.let { manga.title = it }

    // Set artist (if we can find one)
    tags[EH_ARTIST_NAMESPACE]?.let {
        if (it.isNotEmpty()) manga.artist = it.joinToString(transform = Tag::name)
    }
    // Set author (if we can find one)
    tags[EH_AUTHOR_NAMESPACE]?.let {
        if (it.isNotEmpty()) manga.author = it.joinToString(transform = Tag::name)
    }

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
    val titleDesc = buildString {
        title?.let { append("Title: $it\n") }
        altTitle?.let { append("Alternate Title: $it\n") }
    }

    val detailsDesc = buildString {
        uploader?.let { append("Uploader: $it\n") }
        datePosted?.let { append("Posted: ${EX_DATE_FORMAT.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(it))}\n") }
        visible?.let { append("Visible: $it\n") }
        category?.let { append("Category: $it\n") }
        language?.let {
            append("Language: $it")
            if (translated == true) append(" TR")
            append("\n")
        }
        size?.let { append("File Size: ${humanReadableByteCount(it, true)}\n") }
        this@copyTo.length?.let { append("Length: $it pages\n") }
        favorites?.let { append("Favorited: $it times\n") }
        averageRating?.let {
            append("Rating: $it")
            ratingCount?.let { count -> append(" ($count)") }
            append("\n")
        }
    }

    manga.description = listOf(titleDesc, detailsDesc)
        .filter(String::isNotBlank)
        .joinToString(separator = "\n")
}
