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
    val thumbnail: String? = null,
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
)

@Serializable
class Hentai(
    val id: Int,
    val pages: List<Image> = emptyList(),
    @SerialName("media_id") val mediaId: String? = null,
    val cover: Image? = null,
    val thumbnail: Image? = null,
    val scanlator: String? = null,
    val tags: List<Tag> = emptyList(),
    val title: Title = Title(),
    @SerialName("upload_date") private val uploadDate: Long = 0,
    @SerialName("num_favorites") val numFavorites: Long = 0,
    @SerialName("num_pages") val numPages: Int = 0,
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
    val path: String? = null,
    @SerialName("t") val type: String? = null,
    val width: Long? = null,
    val height: Long? = null,
    val thumbnail: String? = null,
)

@Serializable
class Tag(
    val name: String,
    val type: String,
)

@Serializable
class LegacyHentai(
    val id: Int,
    @SerialName("media_id") val mediaId: String? = null,
    val images: LegacyImages = LegacyImages(),
    val tags: List<Tag> = emptyList(),
    val title: Title = Title(),
    @SerialName("upload_date") val uploadDate: Long = 0,
    @SerialName("num_favorites") val numFavorites: Long = 0,
)

@Serializable
class LegacyImages(
    val pages: List<Image> = emptyList(),
    val cover: Image? = null,
    val thumbnail: Image? = null,
)

@Serializable
class CompatGalleryResponse(
    val id: Int,
    @SerialName("media_id") val mediaId: String? = null,
    val title: Title = Title(),
    val images: CompatImages = CompatImages(),
    val scanlator: String? = null,
    @SerialName("upload_date") val uploadDate: Long = 0,
    val tags: List<Tag> = emptyList(),
    @SerialName("num_pages") val numPages: Int = 0,
    @SerialName("num_favorites") val numFavorites: Long = 0,
)

@Serializable
class CompatImages(
    val pages: List<CompatImage> = emptyList(),
    val cover: CompatImage? = null,
    val thumbnail: CompatImage? = null,
)

@Serializable
class CompatImage(
    @SerialName("t") val type: String? = null,
    @SerialName("w") val width: Long? = null,
    @SerialName("h") val height: Long? = null,
)
