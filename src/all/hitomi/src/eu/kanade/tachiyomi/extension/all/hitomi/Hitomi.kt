package eu.kanade.tachiyomi.extension.all.hitomi

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import rx.Observable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalUnsignedTypes::class)
@Source
abstract class Hitomi :
    HttpSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val defaultLanguage: String get() = preferences.getString("default_language", "all") ?: "all"
    private val popularPeriod: String get() = preferences.getString("popular_period", "today") ?: "today"

    private val cdnDomain = "gold-usergeneratedcontent.net"

    private val ltnUrl = "https://ltn.$cdnDomain"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(::imageUrlInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("referer", "$baseUrl/")
        .set("origin", baseUrl)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { nozomiPage(page, "popular", popularPeriod, defaultLanguage) }
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { nozomiPage(page, null, "index", defaultLanguage) }
    }

    @Volatile
    private var searchResponse: Pair<List<String>, IntArray>? = null

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        runBlocking {
            val language = filters.firstInstanceOrNull<LanguageFilter>()?.getLanguage(defaultLanguage) ?: defaultLanguage
            if (filters.firstInstanceOrNull<TypeFilter>()?.state?.none { it.state } == true) {
                return@runBlocking MangasPage(emptyList(), false)
            }
            if (filters.canUseNozomiPage(query)) {
                val sort = filters.firstInstanceOrNull<SelectFilter>()
                return@runBlocking nozomiPage(page, sort?.getArea(), sort?.getValue() ?: "index", language)
            }
            val key = filters.searchKey(query, language)
            val cached = searchResponse
            val ids = if (page > 1 && cached?.first == key) {
                cached.second
            } else {
                hitomiSearch(query.trim(), filters, language).also { searchResponse = key to it }
            }
            val start = (page - 1) * 25
            if (start >= ids.size) return@runBlocking MangasPage(emptyList(), false)
            val end = min(page * 25, ids.size)
            MangasPage(ids.copyOfRange(start, end).toMangaList(), end < ids.size)
        }
    }

    override fun getFilterList() = getFilters(popularPeriod)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "default_language"
            title = "Default language"
            entries = hitomiLanguages.map { it.first }.toTypedArray()
            entryValues = hitomiLanguages.map { it.second }.toTypedArray()
            setDefaultValue("all")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "popular_period"
            title = "Default popular ranking"
            entries = arrayOf("Today", "This week", "This month", "This year")
            entryValues = arrayOf("today", "week", "month", "year")
            setDefaultValue("today")
            summary = "%s"
        }.also(screen::addPreference)
    }

    private fun Int.nextPageRange(): LongRange {
        val byteOffset = ((this - 1) * 25) * 4L
        return byteOffset.until(byteOffset + 100)
    }

    private suspend fun nozomiPage(page: Int, area: String?, tag: String, language: String): MangasPage {
        val range = page.nextPageRange()
        var total: Long? = null
        val ids = getGalleryIDsFromNozomi(area, tag, language, range) {
            total = it.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        }
        return MangasPage(ids.toMangaList(), hasNextNozomiPage(range, total, ids.size))
    }

    private suspend fun getRangedResponse(url: String, range: LongRange?, inspect: (Response) -> Unit = {}): ByteArray {
        val request = when (range) {
            null -> GET(url, headers)

            else -> {
                val rangeHeaders = headersBuilder()
                    .set("Range", "bytes=${range.first}-${range.last}")
                    .build()

                GET(url, rangeHeaders, CacheControl.FORCE_NETWORK)
            }
        }

        val tries = 5
        repeat(tries) { attempt ->
            try {
                return client.newCall(request).awaitSuccess().use {
                    inspect(it)
                    it.body.bytes()
                }
            } catch (e: StreamResetException) {
                if (e.errorCode == ErrorCode.INTERNAL_ERROR) {
                    if (attempt == tries - 1) throw e // last attempt, rethrow
                    Log.e(name, "Stream reset attempt ${attempt + 1}", e)
                    delay((attempt + 1).seconds)
                } else {
                    throw e
                }
            }
        }

        throw Exception("Unreachable code")
    }

    private suspend fun hitomiSearch(
        query: String,
        filters: FilterList,
        language: String = "all",
    ): IntArray = coroutineScope {
        var sortBy: Pair<String?, String> = Pair(null, "index")
        var random = false

        val terms = query
            .trim()
            .lowercase()
            .split(Regex("\\s+"))
            .toMutableList()

        filters.forEach {
            when (it) {
                is SelectFilter -> {
                    sortBy = Pair(it.getArea(), it.getValue())
                    random = (it.vals[it.state].first == "Random")
                }

                is TypeFilter -> {
                    val (activeFilter, inactiveFilters) = it.state.partition { stIt -> stIt.state }
                    terms += when {
                        inactiveFilters.size < 5 -> inactiveFilters.map { fil -> "-type:${fil.value}" }
                        inactiveFilters.size == 5 -> listOf("type:${activeFilter[0].value}")
                        else -> listOf("type: none")
                    }
                }

                is TextFilter -> {
                    if (it.state.isNotEmpty()) {
                        terms += it.state.split(",").filter(String::isNotBlank).map { tag ->
                            val trimmed = tag.trim()
                            buildString {
                                if (trimmed.startsWith('-')) {
                                    append("-")
                                }
                                append(it.type)
                                append(":")
                                append(trimmed.lowercase().removePrefix("-"))
                            }
                        }
                    }
                }

                else -> {}
            }
        }

        // Sorted Nozomi indexes already contain only the selected language.
        if (language != "all" && sortBy == Pair(null, "index") && terms.any { it.isNotBlank() && !it.startsWith('-') }) {
            terms += "language:$language"
        }

        val positiveTerms = LinkedList<String>()
        val negativeTerms = LinkedList<String>()

        for (term in terms) {
            if (term.startsWith("-")) {
                negativeTerms.push(term.removePrefix("-"))
            } else if (term.isNotBlank()) {
                positiveTerms.push(term)
            }
        }

        val version by lazy { async { getGalleriesIndexVersion() } }
        val nodes = mutableMapOf<Long, Deferred<Node>>()
        suspend fun nodeAt(address: Long): Node {
            val request = synchronized(nodes) {
                nodes.getOrPut(address) { async { getGalleryNodeAtAddress(address, version.await()) } }
            }
            return request.await()
        }

        val positiveResults = positiveTerms.distinct().map {
            async {
                try {
                    getGalleryIDsForQuery(it, language, { version.await() }, ::nodeAt)
                } catch (e: IllegalArgumentException) {
                    if (e.message?.equals("HTTP error 404") == true) {
                        throw Exception("Unknown query: \"$it\"")
                    } else {
                        throw e
                    }
                }
            }
        }

        val negativeResults = negativeTerms.distinct().map {
            async {
                try {
                    getGalleryIDsForQuery(it, language, { version.await() }, ::nodeAt)
                } catch (e: IllegalArgumentException) {
                    if (e.message?.equals("HTTP error 404") == true) {
                        throw Exception("Unknown query: \"$it\"")
                    } else {
                        throw e
                    }
                }
            }
        }

        val usePositiveOrder = positiveTerms.isNotEmpty() && sortBy == Pair(null, "index")
        val results = if (positiveTerms.isEmpty() || sortBy != Pair(null, "index")) {
            getGalleryIDsFromNozomi(sortBy.first, sortBy.second, language)
        } else {
            positiveResults.first().await()
        }
        val positives = (if (usePositiveOrder) positiveResults.drop(1) else positiveResults).awaitAll()
        filterGalleryIds(results, positives, negativeResults.awaitAll()).also { if (random) it.shuffle() }
    }

    // search.js
    private suspend fun getGalleryIDsForQuery(
        query: String,
        language: String,
        getVersion: suspend () -> String,
        getNode: suspend (Long) -> Node,
    ): IntArray {
        query.replace("_", " ").let {
            if (it.indexOf(':') > -1) {
                val sides = it.split(":")
                val ns = sides[0]
                var tag = sides[1]

                var area: String? = ns
                var lang = language
                when (ns) {
                    "female", "male" -> {
                        area = "tag"
                        tag = it
                    }

                    "language" -> {
                        area = null
                        lang = tag
                        tag = "index"
                    }
                }

                return getGalleryIDsFromNozomi(area, tag, lang)
            }

            val key = hashTerm(it)
            val node = getNode(0)
            val data = bSearch(key, node, getNode) ?: return IntArray(0)

            return getGalleryIDsFromData(data, getVersion())
        }
    }

    private suspend fun getGalleryIDsFromData(data: Pair<Long, Int>, version: String): IntArray {
        val url = "$ltnUrl/galleriesindex/galleries.$version.data"
        val (offset, length) = data
        require(length in 1..100000000) {
            "Length $length is too long"
        }

        val inbuf = getRangedResponse(url, offset.until(offset + length))

        val buffer =
            ByteBuffer
                .wrap(inbuf)
                .order(ByteOrder.BIG_ENDIAN)

        val numberOfGalleryIDs = buffer.int

        val expectedLength = numberOfGalleryIDs * 4 + 4

        require(numberOfGalleryIDs in 1..10000000) {
            "number_of_galleryids $numberOfGalleryIDs is too long"
        }
        require(inbuf.size == expectedLength) {
            "inbuf.byteLength ${inbuf.size} != expected_length $expectedLength"
        }

        return IntArray(numberOfGalleryIDs) { buffer.int }
    }

    private tailrec suspend fun bSearch(
        key: UByteArray,
        node: Node,
        getNode: suspend (Long) -> Node,
    ): Pair<Long, Int>? {
        fun compareArrayBuffers(
            dv1: UByteArray,
            dv2: UByteArray,
        ): Int {
            val top = min(dv1.size, dv2.size)

            for (i in 0.until(top)) {
                if (dv1[i] < dv2[i]) {
                    return -1
                } else if (dv1[i] > dv2[i]) {
                    return 1
                }
            }

            return 0
        }

        fun locateKey(
            key: UByteArray,
            node: Node,
        ): Pair<Boolean, Int> {
            for (i in node.keys.indices) {
                val cmpResult = compareArrayBuffers(key, node.keys[i])

                if (cmpResult <= 0) {
                    return Pair(cmpResult == 0, i)
                }
            }

            return Pair(false, node.keys.size)
        }

        fun isLeaf(node: Node): Boolean {
            for (subnode in node.subNodeAddresses) {
                if (subnode != 0L) {
                    return false
                }
            }

            return true
        }

        if (node.keys.isEmpty()) {
            return null
        }

        val (there, where) = locateKey(key, node)
        if (there) {
            return node.datas[where]
        } else if (isLeaf(node)) {
            return null
        }

        val nextNode = getNode(node.subNodeAddresses[where])
        return bSearch(key, nextNode, getNode)
    }

    private suspend fun getGalleryIDsFromNozomi(
        area: String?,
        tag: String,
        language: String,
        range: LongRange? = null,
        inspect: (Response) -> Unit = {},
    ): IntArray {
        val nozomiAddress = when (area) {
            null -> "$ltnUrl/$tag-$language.nozomi"
            else -> "$ltnUrl/$area/$tag-$language.nozomi"
        }

        val bytes = getRangedResponse(nozomiAddress, range, inspect)

        return decodeGalleryIds(bytes)
    }

    @Volatile
    private var indexVersion: Pair<Long, String>? = null
    private val indexVersionMutex = Mutex()

    private suspend fun getGalleriesIndexVersion(): String {
        indexVersion?.takeIf { System.nanoTime() - it.first < TimeUnit.MINUTES.toNanos(5) }?.let { return it.second }
        return indexVersionMutex.withLock {
            indexVersion?.takeIf { System.nanoTime() - it.first < TimeUnit.MINUTES.toNanos(5) }?.second ?: client.newCall(
                GET("$ltnUrl/galleriesindex/version?_=${System.currentTimeMillis()}", headers),
            ).awaitSuccess().use {
                it.body.string().trim().also { version -> indexVersion = System.nanoTime() to version }
            }
        }
    }

    private data class Node(
        val keys: List<UByteArray>,
        val datas: List<Pair<Long, Int>>,
        val subNodeAddresses: List<Long>,
    )

    private fun decodeNode(data: ByteArray): Node {
        val buffer = ByteBuffer
            .wrap(data)
            .order(ByteOrder.BIG_ENDIAN)

        val uData = data.toUByteArray()

        val numberOfKeys = buffer.int
        val keys = ArrayList<UByteArray>()

        for (i in 0.until(numberOfKeys)) {
            val keySize = buffer.int

            if (keySize == 0 || keySize > 32) {
                throw Exception("fatal: !keySize || keySize > 32")
            }

            keys.add(uData.sliceArray(buffer.position().until(buffer.position() + keySize)))
            buffer.position(buffer.position() + keySize)
        }

        val numberOfDatas = buffer.int
        val datas = ArrayList<Pair<Long, Int>>()

        for (i in 0.until(numberOfDatas)) {
            val offset = buffer.long
            val length = buffer.int

            datas.add(Pair(offset, length))
        }

        val numberOfSubNodeAddresses = 16 + 1
        val subNodeAddresses = ArrayList<Long>()

        for (i in 0.until(numberOfSubNodeAddresses)) {
            val subNodeAddress = buffer.long
            subNodeAddresses.add(subNodeAddress)
        }

        return Node(keys, datas, subNodeAddresses)
    }

    private suspend fun getGalleryNodeAtAddress(address: Long, version: String): Node {
        val url = "$ltnUrl/galleriesindex/galleries.$version.index"

        val nodedata = getRangedResponse(url, address.until(address + 464))

        return decodeNode(nodedata)
    }

    private fun hashTerm(term: String): UByteArray = sha256(term.toByteArray()).copyOfRange(0, 4).toUByteArray()

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private suspend fun IntArray.toMangaList() = coroutineScope {
        map { id ->
            async {
                try {
                    client.newCall(GET("$ltnUrl/galleries/$id.js", headers))
                        .awaitSuccess()
                        .parseScriptAs<Gallery>()
                        .toSManga()
                } catch (e: IllegalArgumentException) {
                    if (e.message?.equals("HTTP error 404") == true) {
                        return@async null
                    } else {
                        throw e
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun Gallery.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = galleryurl
        author = groups?.joinToString { it.formatted } ?: artists?.joinToString { it.formatted }
        artist = artists?.joinToString { it.formatted }
        genre = tags?.joinToString { it.formatted }
        thumbnail_url = files.first().let {
            "https://$IMAGE_LOOPBACK_HOST/?$IMAGE_THUMBNAIL=true&$IMAGE_GIF=${it.isGif}#${it.hash}"
        }
        description = buildString {
            japaneseTitle?.let {
                append("Japanese title: ", it, "\n")
            }
            parodys?.joinToString { it.formatted }?.let {
                append("Series: ", it, "\n")
            }
            characters?.joinToString { it.formatted }?.let {
                append("Characters: ", it, "\n")
            }
            append("Type: ", type, "\n")
            append("Pages: ", files.size, "\n")
            language?.let { append("Language: ", language) }
        }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url
            .substringAfterLast("-")
            .substringBefore(".")

        return GET("$ltnUrl/galleries/$id.js", headers)
    }

    override fun mangaDetailsParse(response: Response) = runBlocking {
        response.parseScriptAs<Gallery>().toSManga()
    }

    private val openingCache = GalleryOpeningCache()

    private fun openingGallery(url: String, use: GalleryUse): Observable<Gallery> {
        val id = url.substringAfterLast('-').substringBefore('.')
        return openingCache.load(id, use) {
            client.newCall(GET("$ltnUrl/galleries/$id.js", headers)).asObservableSuccess().map { it.parseScriptAs<Gallery>() }
        }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = openingGallery(manga.url, GalleryUse.DETAILS).map { it.toSManga() }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = openingGallery(manga.url, GalleryUse.CHAPTERS).map { it.toChapters() }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = openingGallery(chapter.url, GalleryUse.READER).map { it.toPages() }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response) = response.parseScriptAs<Gallery>().toChapters()

    private fun Gallery.toChapters(): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Chapter"
            url = galleryurl
            scanlator = type
            date_upload = synchronized(dateFormat) { dateFormat.tryParse(date.substringBeforeLast("-")) }
        },
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url
            .substringAfterLast("-")
            .substringBefore(".")

        return GET("$ltnUrl/galleries/$id.js", headers)
    }

    override fun pageListParse(response: Response) = response.parseScriptAs<Gallery>().toPages()

    private fun Gallery.toPages(): List<Page> {
        val id = galleryurl
            .substringAfterLast("-")
            .substringBefore(".")

        val readerUrl = "$baseUrl/reader/$id.html"
        return files.mapIndexed { idx, img ->
            // actual logic in imageUrlInterceptor
            val imageUrl = "https://$IMAGE_LOOPBACK_HOST/?$IMAGE_GIF=${img.isGif}#${img.hash}"

            Page(
                idx,
                readerUrl,
                imageUrl = imageUrl,
            )
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    private inline fun <reified T> Response.parseScriptAs(): T = parseAs<T> { it.substringAfter("var galleryinfo = ") }

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { body -> body }): T {
        val body = use { it.body.string() }
        val transformed = transform(body)

        return transformed.parseAs()
    }

    private suspend fun Call.awaitSuccess() = await().also {
        require(it.isSuccessful) {
            it.close()
            "HTTP error ${it.code}"
        }
    }

    // ------------------ gg.js ------------------
    @Volatile
    private var imageRouting: ImageRouting? = null
    private val mutex = Mutex()

    private suspend fun refreshScript(): ImageRouting = mutex.withLock {
        imageRouting?.takeIf { it.isFresh(System.nanoTime()) } ?: client.newCall(
            GET("$ltnUrl/gg.js?_=${System.currentTimeMillis()}", headers),
        ).awaitSuccess().use { response ->
            parseImageRouting(response.body.string(), System.nanoTime()).also { imageRouting = it }
        }
    }

    private fun imageUrlInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != IMAGE_LOOPBACK_HOST) {
            return chain.proceed(request)
        }

        val hash = request.url.fragment!!
        val isThumbnail = request.url.queryParameter(IMAGE_THUMBNAIL) == "true"
        val isGif = request.url.queryParameter(IMAGE_GIF) == "true"

        val routing = imageRouting?.takeIf { it.isFresh(System.nanoTime()) } ?: runBlocking { refreshScript() }
        val imageUrl = routing.imageUrl(hash, isGif, isThumbnail, cdnDomain)

        val newRequest = request.newBuilder()
            .url(imageUrl)
            .build()

        return chain.proceed(newRequest)
    }

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}

const val IMAGE_LOOPBACK_HOST = "127.0.0.1"
const val IMAGE_THUMBNAIL = "is_thumbnail"
const val IMAGE_GIF = "is_gif"
