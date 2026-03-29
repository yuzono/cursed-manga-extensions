package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
class NHConfig(val image_servers: List<String>, val thumb_servers: List<String>)

@Serializable
class ResultNHentai(val result: List<SearchHentai> = listOf(), val detail: String = "", val per_page: Long = 0)

@Serializable
class SearchHentai(
    var id: Int,
    val english_title: String? = null,
    val japanese_title: String? = null,
    val thumbnail: String,
)

@Serializable
class Hentai(
    var id: Int,
    val pages: List<Image>,
    val thumbnail: Image,
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
class Image(val path: String)

@Serializable
class Tag(
    val name: String,
    val type: String,
)
