package eu.kanade.tachiyomi.extension.all.hitomi

import android.util.Log
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet
import java.util.LinkedList
import java.util.Locale
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalUnsignedTypes::class)
@Source
abstract class Hitomi : KeiSource() {

    private val nozomiLang: String by lazy {
        when (lang) {
            "all" -> "all"
            "en" -> "english"
            "id" -> "indonesian"
            "jv" -> "javanese"
            "ca" -> "catalan"
            "ceb" -> "cebuano"
            "cs" -> "czech"
            "da" -> "danish"
            "de" -> "german"
            "et" -> "estonian"
            "es" -> "spanish"
            "eo" -> "esperanto"
            "fr" -> "french"
            "it" -> "italian"
            "hi" -> "hindi"
            "hu" -> "hungarian"
            "pl" -> "polish"
            "pt" -> "portuguese"
            "vi" -> "vietnamese"
            "tr" -> "turkish"
            "ru" -> "russian"
            "uk" -> "ukrainian"
            "ar" -> "arabic"
            "ko" -> "korean"
            "zh" -> "chinese"
            "ja" -> "japanese"
            else -> ""
        }
    }

    private val cdnDomain = "gold-usergeneratedcontent.net"

    private val ltnUrl = "https://ltn.$cdnDomain"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(this@Hitomi::imageUrlInterceptor)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val entries = getGalleryIDsFromNozomi("popular", "year", nozomiLang, page.nextPageRange())
            .toMangaList()

        return MangasPage(entries, entries.size >= 24)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val entries = getGalleryIDsFromNozomi(null, "index", nozomiLang, page.nextPageRange())
            .toMangaList()

        return MangasPage(entries, entries.size >= 24)
    }

    private class SearchResults(val key: String, val ids: List<Int>)

    @Volatile
    private var lastSearch: SearchResults? = null

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val key = "${query.trim()}|${filters.cacheKey()}"
        val ids = lastSearch?.takeIf { page > 1 && it.key == key }?.ids
            ?: hitomiSearch(query, filters, nozomiLang).also { lastSearch = SearchResults(key, it) }

        val end = min(page * 25, ids.size)
        val entries = ids.subList((page - 1) * 25, end)
            .toMangaList()
        return MangasPage(entries, end < ids.size)
    }

    private fun FilterList.cacheKey() = buildString {
        for (filter in this@cacheKey) {
            when (filter) {
                is SelectFilter -> append(filter.state)
                is TypeFilter -> filter.state.forEach { append(if (it.state) '1' else '0') }
                is TextFilter -> append(filter.state)
                else -> {}
            }
            append('|')
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    private fun Int.nextPageRange(): LongRange {
        val byteOffset = ((this - 1) * 25) * 4L
        return byteOffset.until(byteOffset + 100)
    }

    private suspend fun getRangedResponse(url: String, range: LongRange?): ByteArray {
        val rangeHeaders = range?.let {
            headersBuilder()
                .set("Range", "bytes=${it.first}-${it.last}")
                .build()
        }

        val tries = 5
        repeat(tries) { attempt ->
            try {
                val response = if (rangeHeaders == null) {
                    client.get(url)
                } else {
                    client.get(url, rangeHeaders, CacheControl.FORCE_NETWORK)
                }

                return response.use { it.body.bytes() }
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
    ): List<Int> = coroutineScope {
        var sortBy: Pair<String?, String> = Pair(null, "index")
        var random = false

        val terms = query
            .trim()
            .lowercase()
            .split(WHITESPACE_REGEX)
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

                else -> {}
            }
        }

        if (language != "all" && sortBy == Pair(null, "index") && !terms.any { it.contains(":") }) {
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

        val positiveResults = positiveTerms.map {
            async {
                try {
                    getGalleryIDsForQuery(it, language)
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        throw Exception("Unknown query: \"$it\"")
                    } else {
                        throw e
                    }
                }
            }
        }

        val negativeResults = negativeTerms.map {
            async {
                try {
                    getGalleryIDsForQuery(it, language)
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        throw Exception("Unknown query: \"$it\"")
                    } else {
                        throw e
                    }
                }
            }
        }

        val results = when {
            positiveTerms.isEmpty() || sortBy != Pair(null, "index")
            -> getGalleryIDsFromNozomi(sortBy.first, sortBy.second, language)

            else -> emptySet()
        }.toMutableSet()

        fun filterPositive(newResults: Set<Int>) {
            when {
                results.isEmpty() -> results.addAll(newResults)
                else -> results.retainAll(newResults)
            }
        }

        fun filterNegative(newResults: Set<Int>) {
            results.removeAll(newResults)
        }

        positiveResults.forEach {
            filterPositive(it.await())
        }

        negativeResults.forEach {
            filterNegative(it.await())
        }

        if (random) {
            results.toList().shuffled()
        } else {
            results.toList()
        }
    }

    // search.js
    private suspend fun getGalleryIDsForQuery(
        query: String,
        language: String = "all",
    ): Set<Int> {
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
            val node = getGalleryNodeAtAddress(0)
            val data = bSearch(key, node) ?: return emptySet()

            return getGalleryIDsFromData(data)
        }
    }

    private suspend fun getGalleryIDsFromData(data: Pair<Long, Int>): Set<Int> {
        val url = "$ltnUrl/galleriesindex/galleries.${galleriesIndexVersion()}.data"
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

        val galleryIDs = LinkedHashSet<Int>(numberOfGalleryIDs, 1.0f)

        for (i in 0.until(numberOfGalleryIDs)) {
            galleryIDs.add(buffer.int)
        }

        return galleryIDs
    }

    private tailrec suspend fun bSearch(
        key: UByteArray,
        node: Node,
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

        val nextNode = getGalleryNodeAtAddress(node.subNodeAddresses[where])
        return bSearch(key, nextNode)
    }

    private suspend fun getGalleryIDsFromNozomi(
        area: String?,
        tag: String,
        language: String,
        range: LongRange? = null,
    ): Set<Int> {
        val nozomiAddress = when (area) {
            null -> "$ltnUrl/$tag-$language.nozomi"
            else -> "$ltnUrl/$area/$tag-$language.nozomi"
        }

        val bytes = getRangedResponse(nozomiAddress, range)

        val arrayBuffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN)

        val size = arrayBuffer.remaining() / Int.SIZE_BYTES

        val nozomi = LinkedHashSet<Int>(size, 1.0f)

        while (arrayBuffer.hasRemaining()) {
            nozomi.add(arrayBuffer.int)
        }

        return nozomi
    }

    private val indexVersionMutex = Mutex()
    private var indexVersion: String? = null

    private suspend fun galleriesIndexVersion(): String = indexVersionMutex.withLock {
        indexVersion ?: client.get("$ltnUrl/galleriesindex/version?_=${System.currentTimeMillis()}")
            .use { it.body.string() }
            .also { indexVersion = it }
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

    private suspend fun getGalleryNodeAtAddress(address: Long): Node {
        val url = "$ltnUrl/galleriesindex/galleries.${galleriesIndexVersion()}.index"

        val nodedata = getRangedResponse(url, address.until(address + 464))

        return decodeNode(nodedata)
    }

    private fun hashTerm(term: String): UByteArray = sha256(term.toByteArray()).copyOfRange(0, 4).toUByteArray()

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private suspend fun Collection<Int>.toMangaList() = coroutineScope {
        map { id ->
            async {
                try {
                    client.get("$ltnUrl/galleries/$id.js")
                        .parseScriptAs<Gallery>()
                        .toSManga()
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@async null
                    } else {
                        throw e
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val gallery = client.get("$ltnUrl/galleries/${manga.url.galleryId()}.js")
            .parseScriptAs<Gallery>()

        val chapter = SChapter.create().apply {
            name = "Chapter"
            url = gallery.galleryurl
            scanlator = gallery.type
            date_upload = dateFormat.tryParseDateTime(gallery.date.replace(TIMEZONE_SUFFIX_REGEX, ""))
        }

        return SMangaUpdate(
            manga = if (fetchDetails) gallery.toSManga() else manga,
            chapters = if (fetchChapters) listOf(chapter) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val gallery = client.get("$ltnUrl/galleries/${chapter.url.galleryId()}.js")
            .parseScriptAs<Gallery>()
        val id = gallery.galleryurl.galleryId()

        return gallery.files.mapIndexed { idx, img ->
            // actual logic in imageUrlInterceptor
            val imageUrl = HttpUrl.Builder().apply {
                scheme("https")
                host(IMAGE_LOOPBACK_HOST)
                addQueryParameter(IMAGE_GIF, img.isGif.toString())
                fragment(img.hash)
            }.toString()

            Page(
                idx,
                url = "$baseUrl/reader/$id.html",
                imageUrl = imageUrl,
            )
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", page.url)
            .build()

        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(imageHeaders)
            .get()
            .build()
    }

    private fun String.galleryId() = substringAfterLast("-").substringBefore(".")

    private inline fun <reified T> Response.parseScriptAs(): T = parseAs<T> { it.substringAfter("var galleryinfo = ") }

    private var scriptLastRetrieval: Long? = null
    private val mutex = Mutex()
    private var subdomainOffsetDefault = 0
    private val subdomainOffsetMap = mutableMapOf<Int, Int>()
    private var commonImageId = ""

    private suspend fun refreshScript() = mutex.withLock {
        if (scriptLastRetrieval == null || (scriptLastRetrieval!! + 60000) < System.currentTimeMillis()) {
            val ggScript = client.get("$ltnUrl/gg.js?_=${System.currentTimeMillis()}")
                .use { it.body.string() }

            subdomainOffsetDefault = SUBDOMAIN_DEFAULT_REGEX.find(ggScript)!!.groupValues[1].toInt()
            val o = SUBDOMAIN_BREAK_REGEX.find(ggScript)!!.groupValues[1].toInt()

            subdomainOffsetMap.clear()
            SUBDOMAIN_CASE_REGEX.findAll(ggScript).forEach {
                val case = it.groupValues[1].toInt()
                subdomainOffsetMap[case] = o
            }

            commonImageId = COMMON_ID_REGEX.find(ggScript)!!.groupValues[1]

            scriptLastRetrieval = System.currentTimeMillis()
        }
    }

    // m <-- gg.js
    private suspend fun subdomainOffset(imageId: Int): Int {
        refreshScript()
        return subdomainOffsetMap[imageId] ?: subdomainOffsetDefault
    }

    // b <-- gg.js
    private suspend fun commonImageId(): String {
        refreshScript()
        return commonImageId
    }

    // s <-- gg.js
    private fun imageIdFromHash(hash: String): Int {
        val match = IMAGE_ID_REGEX.find(hash)
        return match!!.groupValues.let { it[2] + it[1] }.toInt(16)
    }

    // real_full_path_from_hash <-- common.js
    private fun thumbPathFromHash(hash: String): String = hash.replace(THUMB_PATH_REGEX, "$2/$1")

    private fun imageUrlInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != IMAGE_LOOPBACK_HOST) {
            return chain.proceed(request)
        }

        val hash = request.url.fragment!!
        val isThumbnail = request.url.queryParameter(IMAGE_THUMBNAIL) == "true"
        val isGif = request.url.queryParameter(IMAGE_GIF) == "true"

        val type = if (isGif) {
            "webp"
        } else {
            "avif"
        }
        val imageId = imageIdFromHash(hash)
        val subDomainOffset = runBlocking { subdomainOffset(imageId) }

        val imageUrl = if (isThumbnail) {
            val subDomain = "${'a' + subDomainOffset}tn"

            "https://$subDomain.$cdnDomain/${type}bigtn/${thumbPathFromHash(hash)}/$hash.$type"
        } else {
            val commonId = runBlocking { commonImageId() }
            val subDomain = if (isGif) {
                "w${subDomainOffset + 1}"
            } else {
                "a${subDomainOffset + 1}"
            }

            "https://$subDomain.$cdnDomain/$commonId$imageId/$hash.$type"
        }

        val newRequest = request.newBuilder()
            .url(imageUrl)
            .build()

        return chain.proceed(newRequest)
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

private val WHITESPACE_REGEX = Regex("\\s+")
private val TIMEZONE_SUFFIX_REGEX = Regex("""(?<=:\d{2})[+-]\d{2}(?::?\d{2})?$""")
private val SUBDOMAIN_DEFAULT_REGEX = Regex("var o = (\\d)")
private val SUBDOMAIN_BREAK_REGEX = Regex("o = (\\d); break;")
private val SUBDOMAIN_CASE_REGEX = Regex("case (\\d+):")
private val COMMON_ID_REGEX = Regex("b: '(.+)'")
private val IMAGE_ID_REGEX = Regex("(..)(.)$")
private val THUMB_PATH_REGEX = Regex("""^.*(..)(.)$""")

const val IMAGE_LOOPBACK_HOST = "127.0.0.1"
const val IMAGE_THUMBNAIL = "is_thumbnail"
const val IMAGE_GIF = "is_gif"
