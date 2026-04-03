package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class NHConfig(
    @SerialName("image_servers") val imageServers: List<String>,
    @SerialName("thumb_servers") val thumbServers: List<String>,
)

@Serializable
class PaginatedResponse<T>(
    val result: List<T> = listOf(),
    @SerialName("per_page") val perPage: Int = 0,
    @SerialName("num_pages") val numPages: Int? = null,
    val total: Int? = null,
)

@Serializable
class GalleryItem(
    val id: Int,
    val thumbnail: String,
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
)

@Serializable
class Hentai(
    val id: Int,
    val pages: List<Image> = emptyList(),
    val thumbnail: Image,
    val tags: List<Tag>,
    val title: Title,
    @SerialName("upload_date") private val uploadDate: Long,
    @SerialName("num_favorites") val numFavorites: Long,
    @SerialName("num_pages") val numPages: Int,
) {
    fun toSChapter() = SChapter.create().apply {
        name = "Chapter"
        scanlator = NHUtils.getGroups(this@Hentai)
        date_upload = uploadDate * 1000
        url = "/g/$id/"
    }
}

@Serializable
class Title(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class Image(
    val path: String,
)

@Serializable
class Tag(
    val name: String,
    val type: String,
)
