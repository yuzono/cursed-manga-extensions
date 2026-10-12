package eu.kanade.tachiyomi.source.model

// Mutable host model; the extension API's factory is a throwing stub.
interface SChapter {
    var url: String
    var name: String
    var date_upload: Long
    var chapter_number: Float
    var scanlator: String?

    companion object {
        fun create(): SChapter = object : SChapter {
            override var url = ""
            override var name = ""
            override var date_upload = 0L
            override var chapter_number = -1f
            override var scanlator: String? = null
        }
    }
}
