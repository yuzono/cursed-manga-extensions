package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class NHConfig(
    @SerialName("image_servers") val imageServers: List<String>,
    @SerialName("thumb_servers") val thumbServers: List<String>,
)

@Serializable
class PaginatedResponse(
    val result: List<GallerySearchItem> = listOf(),
    @SerialName("per_page") val perPage: Int = 0,
    @SerialName("num_pages") val numPages: Int? = null,
    val total: Int? = null,
)

@Serializable
class GallerySearchItem(
    var id: Int,
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
    val thumbnail: String,
)

@Serializable
class Gallery(
    var id: Int,
    val pages: List<Image>,
    val thumbnail: Image,
    val tags: List<Tag>,
    val title: Title,
    @SerialName("upload_date") val uploadDate: Long,
    @SerialName("num_favorites") val numFavorites: Long,
)

@Serializable
class Title(
    var english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class Image(val path: String)

@Serializable
class Tag(
    val name: String,
    val type: String,
)
