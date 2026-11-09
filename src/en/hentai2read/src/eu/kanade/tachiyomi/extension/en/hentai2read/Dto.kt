package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListingDto(private val props: Props) {
    @Serializable
    class Props(
        val books: Books? = null,
        // Rankings list theirs as `podium` (top three, page 1 only) and `grid` instead.
        val podium: List<Book> = emptyList(),
        val grid: List<Book> = emptyList(),
    )

    @Serializable
    class Books(val data: List<Book> = emptyList())

    @Serializable
    class Book(val id: Int, val pageCount: Int? = null, val chapters: Int? = null)

    val books get() = props.books?.data.orEmpty() + props.podium + props.grid
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
    )

    @Serializable
    class Book(val id: Int)

    @Serializable
    class Chapter(
        val number: String? = null,
        val title: String? = null,
        val url: String,
        val date: String? = null,
        val isMembers: Boolean = false,
    )

    val id get() = props.book.id
    val chapters get() = props.chapters
}

@Serializable
class ReaderDto(private val props: Props) {

    @Serializable
    class Props(
        val images: List<String?> = emptyList(),
        val avif: List<String?> = emptyList(),
    )

    // `avif` holds the same pages by index, so it fills any page missing from `images`.
    fun toPages(): List<Page> = (0 until maxOf(props.images.size, props.avif.size))
        .mapNotNull { props.images.getOrNull(it) ?: props.avif.getOrNull(it) }
        .mapIndexed { index, url -> Page(index, imageUrl = if (url.startsWith("//")) "https:$url" else url) }
}
