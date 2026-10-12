package eu.kanade.tachiyomi.extension.all.ehentai

import android.net.Uri

/**
 * Gallery metadata storage model
 */

class ExGalleryMetadata {
    var url: String? = null

    var thumbnailUrl: String? = null

    var title: String? = null
    var altTitle: String? = null

    var category: String? = null

    var datePosted: Long? = null
    var parent: String? = null
    var visible: String? = null // Not a boolean
    var language: String? = null
    var translated: Boolean? = null
    var size: Long? = null
    var length: Int? = null
    var favorites: Int? = null
    var ratingCount: Int? = null
    var averageRating: Double? = null

    var uploader: String? = null

    val tags: MutableMap<String, List<Tag>> = mutableMapOf()

    companion object {
        private fun splitGalleryUrl(url: String) = url.let {
            // Only parse URL if is full URL
            val pathSegments = if (it.startsWith("http")) {
                Uri.parse(it).pathSegments
            } else {
                it.split('/')
            }
            pathSegments.filterNot(String::isNullOrBlank)
        }

        fun galleryId(url: String) = splitGalleryUrl(url)[1]

        fun normalizeUrl(url: String): String {
            val segments = splitGalleryUrl(url)
            return "/g/${segments[1]}/${segments[2]}/?nw=always"
        }
    }
}
