package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListingDto(val props: Props) {
    @Serializable
    class Props(val books: Books? = null)

    @Serializable
    class Books(val data: List<Book> = emptyList())

    @Serializable
    class Book(val id: Int, val pageCount: Int? = null, val chapters: Int? = null)
}

@Serializable
class JsonLdDto(
    @SerialName("@type") val type: String? = null,
    val numberOfPages: Int? = null,
)

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

// `type` is the URL segment (category, content).
@Serializable
class TagDto(
    private val id: Int,
    private val name: String,
    val type: String,
) {
    internal fun toFilter() = TagFilter(id, name)
}

@Serializable
class BookPageDto(val props: Props) {
    @Serializable
    class Props(val book: Book)

    @Serializable
    class Book(val pageCount: Int? = null)
}

@Serializable
class ReaderDto(private val props: Props) {

    @Serializable
    class Props(
        val images: List<String?>? = null,
        val avif: List<String?>? = null,
    )

    // `images` holds the webp pages; `avif` repeats the same pages, so only one list is used.
    fun toPages(): List<Page> = props.images.orEmpty().filterNotNull().ifEmpty { props.avif.orEmpty().filterNotNull() }
        .mapNotNull(::toHttpsUrl)
        .mapIndexed { index, url -> Page(index, imageUrl = url) }

    // Protocol-relative URLs are upgraded to HTTPS; anything else that isn't HTTPS is dropped.
    private fun toHttpsUrl(url: String): String? = when {
        url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> null
    }
}
