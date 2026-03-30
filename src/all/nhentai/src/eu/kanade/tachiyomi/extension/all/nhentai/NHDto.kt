package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PaginatedResponse<T>(
    val result: List<T>,
    @SerialName("num_pages") val numPages: Int,
)

@Serializable
class GalleryListItem(
    private val id: Int,
    private val thumbnail: String,
    @SerialName("english_title") private val englishTitle: String? = null,
    @SerialName("japanese_title") private val japaneseTitle: String? = null,
) {
    fun toSManga(displayFullTitle: Boolean, shortenTitle: (String) -> String, thumbServer: String) = SManga.create().apply {
        url = "/g/$id/"
        val rawTitle = englishTitle ?: japaneseTitle!!
        title = if (displayFullTitle) rawTitle else shortenTitle(rawTitle)
        thumbnail_url = "$thumbServer/$thumbnail"
    }
}

@Serializable
class Hentai(
    val id: Int,
    private val title: NHTitle,
    private val thumbnail: ImageInfo,
    @SerialName("upload_date") private val uploadDate: Long,
    val tags: List<NHTag>,
    @SerialName("num_pages") val numPages: Int,
    @SerialName("num_favorites") private val numFavorites: Int,
    val pages: List<NHPage> = emptyList(),
) {
    fun toSManga(full: Boolean, displayFullTitle: Boolean, shortenTitle: (String) -> String, thumbServer: String) = SManga.create().apply {
        url = "/g/$id/"
        title = if (displayFullTitle) {
            this@Hentai.title.english ?: this@Hentai.title.japanese ?: this@Hentai.title.pretty!!
        } else {
            this@Hentai.title.pretty ?: (this@Hentai.title.english ?: this@Hentai.title.japanese)!!.let(shortenTitle)
        }
        thumbnail_url = "$thumbServer/${thumbnail.path}"

        if (full) {
            status = SManga.COMPLETED
            artist = NHUtils.getArtists(this@Hentai)
            author = NHUtils.getGroups(this@Hentai) ?: NHUtils.getArtists(this@Hentai)
            description = "Full English and Japanese titles:\n"
                .plus("${this@Hentai.title.english ?: this@Hentai.title.japanese ?: this@Hentai.title.pretty ?: ""}\n")
                .plus(this@Hentai.title.japanese ?: "")
                .plus("\n\n")
                .plus("Pages: $numPages\n")
                .plus("Favorited by: $numFavorites\n")
                .plus(NHUtils.getTagDescription(this@Hentai))
            genre = NHUtils.getTags(this@Hentai)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    fun toSChapter() = SChapter.create().apply {
        name = "Chapter"
        scanlator = NHUtils.getGroups(this@Hentai)
        date_upload = uploadDate * 1000
        url = "/g/$id/"
    }
}

@Serializable
class NHTitle(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class ImageInfo(
    val path: String,
)

@Serializable
class NHTag(
    val type: String,
    val name: String,
)

@Serializable
class NHPage(
    val path: String,
)

@Serializable
class Config(
    @SerialName("image_servers") val imageServers: List<String>,
    @SerialName("thumb_servers") val thumbServers: List<String>,
)
