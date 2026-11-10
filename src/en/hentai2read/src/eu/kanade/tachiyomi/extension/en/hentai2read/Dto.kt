package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val MEMO_PAGE_COUNT = "pageCount"

@Serializable
class ListingDto(private val props: Props) {
    @Serializable
    class Props(
        val books: Books? = null,
        // Rankings list theirs as `podium` (top three, page 1 only) and `grid` instead, and page with `pages`.
        val podium: List<Book> = emptyList(),
        val grid: List<Book> = emptyList(),
        val pages: Pages? = null,
    )

    @Serializable
    class Books(val data: List<Book> = emptyList(), val page: Int = 1, val lastPage: Int = 1)

    @Serializable
    class Pages(val page: Int = 1, val lastPage: Int = 1)

    @Serializable
    class Book(
        val id: Int,
        private val title: String,
        private val url: String,
        private val cover: String? = null,
        private val artist: TagDto? = null,
        private val chapters: Int? = null,
        val pageCount: Int? = null,
    ) {
        fun toSManga() = SManga.create().apply {
            url = this@Book.url
            title = this@Book.title
            thumbnail_url = cover
            this@Book.artist?.name?.takeIf { it != "Unknown" }?.let {
                artist = it
                author = it
            }
            // A multi-chapter book's own page has no total, so it's carried over from the listing.
            if ((chapters ?: 0) > 1 && pageCount != null) memo = buildJsonObject { put(MEMO_PAGE_COUNT, pageCount) }
        }
    }

    val books get() = props.books?.data.orEmpty() + props.podium + props.grid
    val hasNextPage get() = props.books?.run { page < lastPage } ?: props.pages?.run { page < lastPage } ?: false
}

@Serializable
class JsonLdDto(
    @SerialName("@type") private val type: String? = null,
    private val numberOfPages: Int? = null,
) {
    val bookPages get() = numberOfPages.takeIf { type == "Book" }
}

@Serializable
class TagPageDto(private val props: Props) {

    @Serializable
    class Props(val tags: Tags)

    @Serializable
    class Tags(
        val data: List<TagDto>,
        val lastPage: Int,
    )

    val tags get() = props.tags.data
    val lastPage get() = props.tags.lastPage
}

@Serializable
class TagDto(
    private val id: Int,
    val name: String,
    val type: String,
    private val url: String,
) {
    internal fun toFilter() = TagFilter(id, name, url)
    internal fun toArtistFilter() = ArtistFilter(id, name, url)
}

@Serializable
class BookPageDto(private val props: Props) {
    @Serializable
    class Props(
        val book: Book,
        val chapters: List<Chapter> = emptyList(),
        val related: List<Related> = emptyList(),
    )

    @Serializable
    class Book(
        val id: Int,
        val title: String,
        val url: String,
        val cover: String? = null,
        val alt: String? = null,
        val type: Label? = null,
        val status: Label? = null,
        // Not seen filled in yet, so its type is left open.
        val year: JsonPrimitive? = null,
        val tags: List<TagDto> = emptyList(),
        val groups: List<Group> = emptyList(),
    )

    @Serializable
    class Label(val label: String)

    // Artist, parody, character and language tags.
    @Serializable
    class Group(val type: String, val tags: List<TagDto> = emptyList())

    @Serializable
    class Chapter(
        val number: String? = null,
        val title: String? = null,
        val url: String,
        val date: String? = null,
        val isMembers: Boolean = false,
    )

    @Serializable
    class Related(
        private val title: String,
        private val url: String,
        private val cover: String? = null,
    ) {
        fun toSManga() = SManga.create().apply {
            title = this@Related.title
            url = this@Related.url
            thumbnail_url = cover
        }
    }

    val id get() = props.book.id
    val title get() = props.book.title
    val chapters get() = props.chapters
    val related get() = props.related

    fun toSManga(pages: Int?) = SManga.create().apply {
        val book = props.book
        fun group(type: String) = book.groups.filter { it.type == type }.flatMap { it.tags }.map { it.name }

        url = book.url
        title = book.title
        thumbnail_url = book.cover

        val artists = group("artist").filter { it != "Unknown" }.joinToString().ifEmpty { null }
        artist = artists
        author = artists

        genre = book.tags.joinToString { it.name }
        val statusLabel = book.status?.label.orEmpty().lowercase()
        status = when {
            "ongoing" in statusLabel -> SManga.ONGOING
            "completed" in statusLabel -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        description = buildString {
            fun section(label: String, value: String?) {
                if (value.isNullOrEmpty()) return
                if (isNotEmpty()) append("\n\n")
                append(label, ":\n", value)
            }

            section("Alternative Title", book.alt)
            section("Parody", group("parody").joinToString())
            section("Character", group("character").joinToString())
            section("Type", book.type?.label)
            section("Pages", pages?.toString())
            section("Language", group("language").joinToString())
            section("Released", book.year?.contentOrNull)
        }
    }
}

@Serializable
class ReaderDto(private val props: Props) {

    @Serializable
    class Props(
        val images: List<String?> = emptyList(),
        val avif: List<String?> = emptyList(),
    )

    // `avif` holds the same pages by index, so either list fills a page missing from the other
    fun toPages(preferAvif: Boolean): List<Page> = (0 until maxOf(props.images.size, props.avif.size))
        .mapNotNull {
            val webp = props.images.getOrNull(it)
            val avif = props.avif.getOrNull(it)
            if (preferAvif) avif ?: webp else webp ?: avif
        }
        .mapIndexed { index, url -> Page(index, imageUrl = if (url.startsWith("//")) "https:$url" else url) }
}
