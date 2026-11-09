package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
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
    private val id: Int,
    private val thumbnail: String,
    @SerialName("english_title") private val englishTitle: String? = null,
    @SerialName("japanese_title") private val japaneseTitle: String? = null,
) {
    fun toSMangaOrNull(thumbServer: String, fullTitle: Boolean): SManga? {
        val rawTitle = englishTitle ?: japaneseTitle ?: return null
        return SManga.create().apply {
            url = "/g/$id/"
            title = if (fullTitle) rawTitle else rawTitle.shortenTitle()
            thumbnail_url = "$thumbServer/$thumbnail"
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }
}

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
    fun toSManga(thumbServer: String, fullTitle: Boolean) = SManga.create().apply {
        val titles = this@Hentai.title
        url = "/g/$id/"
        title = if (fullTitle) {
            titles.english ?: titles.japanese ?: titles.pretty!!
        } else {
            titles.pretty ?: (titles.english ?: titles.japanese)!!.shortenTitle()
        }
        thumbnail_url = "$thumbServer/${thumbnail.path}"
        status = SManga.COMPLETED
        artist = getArtists(this@Hentai)
        author = getGroups(this@Hentai) ?: getArtists(this@Hentai)
        description = buildString {
            append("Full English and Japanese titles:\n")
            append("${titles.english ?: titles.japanese ?: titles.pretty ?: ""}\n")
            append(titles.japanese ?: "")
            append("\n\n")
            append("Pages: $numPages\n")
            append("Favorited by: $numFavorites\n")
            append(getTagDescription(this@Hentai))
        }
        genre = getTags(this@Hentai)
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    fun toSChapter() = SChapter.create().apply {
        name = "Chapter"
        scanlator = getGroups(this@Hentai)
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
