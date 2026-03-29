package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
class NHConfig(val image_servers: List<String>, val thumb_servers: List<String>)

@Serializable
class HentaiData(val body: String)

@Serializable
class Hentai(
    var id: Int,
    val pages: List<Image>,
    val media_id: String,
    val tags: List<Tag>,
    val title: Title,
    val upload_date: Long,
    val num_favorites: Long,
)

@Serializable
class Title(
    var english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class Image(
    val path: String,
) {
    val extension
        get() = when (path) {
            "w" -> "webp"
            "p" -> "png"
            "g" -> "gif"
            else -> "jpg"
        }
}

@Serializable
class Tag(
    val name: String,
    val type: String,
)
