package eu.kanade.tachiyomi.extension.all.hitomi

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

@Serializable
class Gallery(
    val galleryurl: String,
    val title: String,
    val japaneseTitle: String?,
    val date: String,
    val type: String?,
    val language: String?,
    val tags: List<Tag>?,
    val artists: List<Artist>?,
    val groups: List<Group>?,
    val characters: List<Character>?,
    val parodys: List<Parody>?,
    val files: List<ImageFile>,
)

@Serializable
class ImageFile(
    val hash: String,
    private val name: String,
) {
    val isGif get() = name.endsWith(".gif") || name.endsWith(".webp")
}

@Serializable
class Tag(
    private val female: JsonPrimitive?,
    private val male: JsonPrimitive?,
    private val tag: String,
) {
    val formatted get() = if (female?.content == "1") {
        tag.toCamelCase() + " ♀"
    } else if (male?.content == "1") {
        tag.toCamelCase() + " ♂"
    } else {
        tag.toCamelCase()
    }
}

@Serializable
class Artist(
    private val artist: String,
) {
    val formatted get() = artist.toCamelCase()
}

@Serializable
class Group(
    private val group: String,
) {
    val formatted get() = group.toCamelCase()
}

@Serializable
class Character(
    private val character: String,
) {
    val formatted get() = character.toCamelCase()
}

@Serializable
class Parody(
    private val parody: String,
) {
    val formatted get() = parody.toCamelCase()
}

internal fun Gallery.toSManga() = SManga.create().apply {
    title = this@toSManga.title
    url = galleryurl
    author = groups?.joinToString { it.formatted } ?: artists?.joinToString { it.formatted }
    artist = artists?.joinToString { it.formatted }
    genre = tags?.joinToString { it.formatted }
    thumbnail_url = files.first().let {
        HttpUrl.Builder().apply {
            scheme("https")
            host(IMAGE_LOOPBACK_HOST)
            addQueryParameter(IMAGE_THUMBNAIL, "true")
            addQueryParameter(IMAGE_GIF, it.isGif.toString())
            fragment(it.hash)
        }.toString()
    }
    description = buildString {
        japaneseTitle?.let {
            append("Japanese title: ", it, "\n")
        }
        parodys?.joinToString { it.formatted }?.let {
            append("Series: ", it, "\n")
        }
        characters?.joinToString { it.formatted }?.let {
            append("Characters: ", it, "\n")
        }
        type?.let { append("Type: ", it, "\n") }
        append("Pages: ", files.size, "\n")
        language?.let { append("Language: ", language) }
    }
    status = SManga.COMPLETED
    update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
}

private fun String.toCamelCase(): String = buildString(length) {
    var capitalize = true
    for (char in this@toCamelCase) {
        append(
            if (capitalize) {
                char.uppercase()
            } else {
                char.lowercase()
            },
        )
        capitalize = char.isWhitespace()
    }
}
