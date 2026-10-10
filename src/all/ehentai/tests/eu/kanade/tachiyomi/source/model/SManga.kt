package eu.kanade.tachiyomi.source.model

// Mutable host model; the extension API's factory is a throwing stub.
interface SManga {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var update_strategy: UpdateStrategy
    var initialized: Boolean

    companion object {
        const val ONGOING = 1
        const val COMPLETED = 2

        fun create(): SManga = object : SManga {
            override var url = ""
            override var title = ""
            override var artist: String? = null
            override var author: String? = null
            override var description: String? = null
            override var genre: String? = null
            override var status = 0
            override var thumbnail_url: String? = null
            override var update_strategy = UpdateStrategy.ALWAYS_UPDATE
            override var initialized = false
        }
    }
}
