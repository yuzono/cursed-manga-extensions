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
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SeedRandom
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalUnsignedTypes::class)
@Source
abstract class Hitomi : KeiSource() {

    private val nozomiLang: String
        get() = when (lang) {
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

    private val cdnDomain = "gold-usergeneratedcontent.net"

    private val ltnUrl = "https://ltn.$cdnDomain"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(this@Hitomi::imageUrlInterceptor)
        // Full nozomi files are megabytes, more than the app's own http cache holds
        cache(Cache(File(applicationContext.cacheDir, "hitomi_cache"), 50L * 1024 * 1024))
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val ids = nozomiList("popular", "year", nozomiLang).readPage(page)
        return MangasPage(ids.toMangaList(), ids.size == PAGE_SIZE)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val ids = nozomiList(null, "index", nozomiLang).readPage(page)
        return MangasPage(ids.toMangaList(), ids.size == PAGE_SIZE)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val search = parseSearch(query, filters, nozomiLang)

        if (search.isSingleList) {
            val term = search.positive.singleOrNull()
            val list = if (term == null) nozomiList(search.sort.first, search.sort.second, nozomiLang) else resolveTerm(term, nozomiLang)
            val ids = list?.readPage(page, term).orEmpty()
            return MangasPage(ids.toMangaList(), ids.size == PAGE_SIZE)
        }

        val ids = search.galleryIds(seed = "$query|${filters.stateKey()}")
        val start = min((page - 1) * PAGE_SIZE, ids.size)
        val end = min(page * PAGE_SIZE, ids.size)
        return MangasPage(ids.subList(start, end).toMangaList(), end < ids.size)
    }

    private fun FilterList.stateKey() = buildString {
        for (filter in this@stateKey) {
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
                    client.get(url, Fetch.XHR)
                } else {
                    client.get(url, rangeHeaders, Fetch.XHR, CacheControl.FORCE_NETWORK)
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

    private class Search(
        val sort: Pair<String?, String>,
        val random: Boolean,
        val positive: List<String>,
        val negative: List<String>,
    ) {
        val isSingleList get() = !random && negative.isEmpty() && (positive.isEmpty() || (positive.size == 1 && sort == DEFAULT_SORT))
    }

    private fun parseSearch(query: String, filters: FilterList, language: String): Search {
        var sort = DEFAULT_SORT
        var random = false

        val terms = query
            .trim()
            .lowercase()
            .split(WHITESPACE_REGEX)
            .toMutableList()

        filters.forEach {
            when (it) {
                is SelectFilter -> {
                    sort = Pair(it.getArea(), it.getValue())
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

        if (language != "all" && sort == DEFAULT_SORT && !terms.any { it.contains(":") }) {
            terms += "language:$language"
        }

        val (negative, positive) = terms.filter(String::isNotBlank).partition { it.startsWith("-") }
        return Search(sort, random, positive, negative.map { it.removePrefix("-") })
    }

    private suspend fun Search.galleryIds(seed: String): List<Int> = coroutineScope {
        val positiveIds = positive.map { async { termIds(it) } }
        val negativeIds = negative.map { async { termIds(it) } }

        var results: MutableSet<Int>? = if (positive.isEmpty() || sort != DEFAULT_SORT) {
            nozomiList(sort.first, sort.second, nozomiLang).readAll().toCollection(LinkedHashSet())
        } else {
            null
        }

        for (ids in positiveIds) {
            val termIds = ids.await()
            results = results?.apply { retainAll(termIds.toSet()) } ?: termIds.toCollection(LinkedHashSet())
        }
        for (ids in negativeIds) {
            results?.removeAll(ids.await().toSet())
        }

        val list = results.orEmpty().toList()
        if (random) SeedRandom(seed).shuffle(list) else list
    }

    private suspend fun termIds(term: String): List<Int> = resolveTerm(term, nozomiLang)?.readAll(term).orEmpty()

    // The gallery ids of a nozomi file, or of an index data block, as a byte range of big-endian ints
    private class IdList(val url: String, val offset: Long = 0, val length: Long? = null)

    private fun nozomiList(area: String?, tag: String, language: String) = IdList(
        when (area) {
            null -> "$ltnUrl/$tag-$language.nozomi"
            else -> "$ltnUrl/$area/$tag-$language.nozomi"
        },
    )

    // search.js
    private suspend fun resolveTerm(term: String, language: String): IdList? {
        val query = term.replace("_", " ")
        if (query.indexOf(':') > -1) {
            val sides = query.split(":")
            val ns = sides[0]
            var tag = sides[1]

            var area: String? = ns
            var lang = language
            when (ns) {
                "female", "male" -> {
                    area = "tag"
                    tag = query
                }

                "language" -> {
                    area = null
                    lang = tag
                    tag = "index"
                }
            }

            return nozomiList(area, tag, lang)
        }

        val version = galleriesIndexVersion()
        val (offset, length) = bSearch(hashTerm(query), getGalleryNodeAtAddress(0, version), version) ?: return null
        // A data block starts with the number of ids that follow
        return IdList("$ltnUrl/galleriesindex/galleries.$version.data", offset + 4, length - 4L)
    }

    private suspend fun IdList.readAll(term: String? = null): List<Int> = unknownQueryOn404(term) {
        parseIds(getRangedResponse(url, length?.let { offset.until(offset + it) }))
    }

    private suspend fun IdList.readPage(page: Int, term: String? = null): List<Int> {
        val start = offset + (page - 1) * PAGE_SIZE * 4L
        val end = min(start + PAGE_SIZE * 4L, length?.let { offset + it } ?: Long.MAX_VALUE)
        if (start >= end) return emptyList()

        return try {
            unknownQueryOn404(term) { parseIds(getRangedResponse(url, start.until(end))) }
        } catch (e: HttpException) {
            // Past the end of a nozomi file, whose length isn't known up front
            if (e.code == 416) emptyList() else throw e
        }
    }

    private inline fun <T> unknownQueryOn404(term: String?, block: () -> T): T = try {
        block()
    } catch (e: HttpException) {
        if (e.code == 404 && term != null) throw Exception("Unknown query: \"$term\"") else throw e
    }

    private fun parseIds(bytes: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return List(buffer.remaining() / Int.SIZE_BYTES) { buffer.int }
    }

    private tailrec suspend fun bSearch(
        key: UByteArray,
        node: Node,
        version: String,
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

        val nextNode = getGalleryNodeAtAddress(node.subNodeAddresses[where], version)
        return bSearch(key, nextNode, version)
    }

    private val indexVersionMutex = Mutex()
    private var indexVersion: String? = null
    private var indexVersionRetrieval = 0L

    private suspend fun galleriesIndexVersion(): String = indexVersionMutex.withLock {
        indexVersion?.takeIf { indexVersionRetrieval + 60000 > System.currentTimeMillis() }
            ?: client.get("$ltnUrl/galleriesindex/version?_=${System.currentTimeMillis()}", Fetch.XHR)
                .use { it.body.string() }
                .also {
                    indexVersion = it
                    indexVersionRetrieval = System.currentTimeMillis()
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

    private suspend fun Collection<Int>.toMangaList(include: (Gallery) -> Boolean = { true }) = coroutineScope {
        map { id ->
            async {
                try {
                    getGallery(id.toString()).takeIf(include)?.toSManga()
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

    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = getGallery(manga.url.galleryId()).related
        .toMangaList { nozomiLang == "all" || it.language == nozomiLang }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val id = url.pathSegments.last().galleryId().toIntOrNull() ?: return null

        return getGallery(id.toString()).toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val gallery = getGallery(manga.url.galleryId())

        val chapter = SChapter.create().apply {
            name = "Chapter"
            url = gallery.galleryurl
            scanlator = gallery.type
            date_upload = dateFormat.tryParseZonedDateTime(gallery.date)
        }

        return SMangaUpdate(
            manga = gallery.toSManga(),
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val gallery = getGallery(chapter.url.galleryId())
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

    override fun getImageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headers)
        .fetch(Fetch.IMAGE.from(page.url))
        .build()

    private suspend fun getGallery(id: String): Gallery = client.get("$ltnUrl/galleries/$id.js", Fetch.SCRIPT).parseScriptAs()

    private fun String.galleryId() = substringAfterLast("-").substringBefore(".")

    private inline fun <reified T> Response.parseScriptAs(): T = parseAs<T> { it.substringAfter("var galleryinfo = ") }

    private var scriptLastRetrieval: Long? = null
    private val mutex = Mutex()
    private var subdomainOffsetDefault = 0
    private val subdomainOffsetMap = mutableMapOf<Int, Int>()
    private var commonImageId = ""

    private suspend fun refreshScript() = mutex.withLock {
        if (scriptLastRetrieval == null || (scriptLastRetrieval!! + 60000) < System.currentTimeMillis()) {
            val ggScript = client.get("$ltnUrl/gg.js?_=${System.currentTimeMillis()}", Fetch.SCRIPT)
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
        // Animated avif plays with Mihon's new decoder
        val useWebp = !hasNewDecoder && request.url.queryParameter(IMAGE_GIF) == "true"

        val type = if (useWebp) {
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
            val subDomain = if (useWebp) {
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

    // Mihon v0.21.0+
    private val hasNewDecoder by lazy { runCatching { Class.forName("ca.mpreg.imagedecoder.ImageDecoder") }.isSuccess }
}

private const val PAGE_SIZE = 25
private val DEFAULT_SORT: Pair<String?, String> = Pair(null, "index")

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S]X", Locale.ENGLISH)

private val WHITESPACE_REGEX = Regex("\\s+")
private val SUBDOMAIN_DEFAULT_REGEX = Regex("var o = (\\d)")
private val SUBDOMAIN_BREAK_REGEX = Regex("o = (\\d); break;")
private val SUBDOMAIN_CASE_REGEX = Regex("case (\\d+):")
private val COMMON_ID_REGEX = Regex("b: '(.+)'")
private val IMAGE_ID_REGEX = Regex("(..)(.)$")
private val THUMB_PATH_REGEX = Regex("""^.*(..)(.)$""")

const val IMAGE_LOOPBACK_HOST = "127.0.0.1"
const val IMAGE_THUMBNAIL = "is_thumbnail"
const val IMAGE_GIF = "is_gif"
