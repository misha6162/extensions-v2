package eu.kanade.tachiyomi.extension.all.pawchive

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.pawchive.PawchiveCreatorDto.Companion.serviceName
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.io.use
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Pawchive :
    KeiSource(),
    ConfigurableSource {

    private val preferences = getPreferences()

    private val apiPath = "api/v1"

    private val dataPath = "data"

    private val fileUrl = baseUrl.replace("//", "//file.")

    private val imgUrl = baseUrl.replace("//", "//img.")

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor { chain ->
            val request = chain.request()

            val modifiedRequest = when {
                request.url.pathSegments.firstOrNull() == "api" -> {
                    request.newBuilder()
                        .header("Accept", "text/css")
                        .build()
                }

                request.url.toString().startsWith(fileUrl) || request.url.toString().startsWith(imgUrl) -> {
                    request.newBuilder()
                        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .header("Sec-Fetch-Dest", "image")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Site", "same-site")
                        .build()
                }

                else -> request
            }

            var response = chain.proceed(modifiedRequest)

            if (response.code == 404 && request.url.toString().startsWith(fileUrl)) {
                if (preferences.getBoolean(FALLBACK_LOW_RES_IMG, true)) {
                    response.close()

                    val fallbackRequest = request.newBuilder()
                        .url(request.url.toString().toThumbnailUrl())
                        .build()
                    response = chain.proceed(fallbackRequest)
                }
            }

            response
        }
        connectTimeout(15.seconds)
        readTimeout(30.seconds)
        writeTimeout(15.seconds)
    }

    private val apiClient = client.newBuilder()
        .rateLimit(2)
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", getFilterList(null))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", getLatestUpdatesFilterList())

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        when (filters.firstInstanceOrNull<ModeFilter>()?.state ?: 0) {
            1 -> return searchPosts(page, query, filters, popular = false)
            2 -> return searchPosts(page, query, filters, popular = true)
        }

        var sort: Pair<String, String> = "" to ""
        val typeIncluded = mutableListOf<String>()
        val typeExcluded = mutableListOf<String>()
        var fav: Boolean? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.getValue() to if (filter.state!!.ascending) "asc" else "desc"
                is TypeFilter -> {
                    filter.state.forEach { tri ->
                        if (tri.isIncluded()) typeIncluded.add(tri.value)
                        if (tri.isExcluded()) typeExcluded.add(tri.value)
                    }
                }
                is FavoritesFilter -> {
                    fav = when (filter.state[0].state) {
                        1 -> true
                        2 -> false
                        else -> null
                    }
                }
                else -> {}
            }
        }

        if (sort.first.isEmpty()) {
            sort = "pop" to "desc"
        }

        val mangas = coroutineScope {
            val favoritesDeferred = async(Dispatchers.IO) {
                if (fav != null) {
                    val url = "$baseUrl/$apiPath/account/favorites"

                    apiClient.get(url, ensureSuccess = false).use { response ->
                        if (response.isSuccessful) {
                            response.parseAs<List<PawchiveFavoritesDto>>()
                        } else {
                            val message = if (response.code == 401) "You are not logged in" else "HTTP error ${response.code}"
                            throw Exception("Failed to fetch favorites: $message")
                        }
                    }
                } else {
                    emptyList()
                }
            }

            val creatorsDeferred = async(Dispatchers.IO) {
                val url = "$baseUrl/$apiPath/creators"
                val cacheControl = CacheControl.Builder().maxStale(30.minutes).build()

                apiClient.get(url, cacheControl).use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP error ${response.code}")
                    }
                    response.parseAs<List<PawchiveCreatorDto>>()
                }
            }

            val favorites = favoritesDeferred.await()
            val allCreators = creatorsDeferred.await()

            allCreators.filter { creator ->
                val sName = creator.service.serviceName().lowercase()
                val includeType = typeIncluded.isEmpty() || typeIncluded.contains(sName)
                val excludeType = typeExcluded.isNotEmpty() && typeExcluded.contains(sName)
                val regularSearch = creator.name.contains(query, true)

                val favEntry = favorites.find { f -> f.id == creator.id }
                creator.fav = favEntry?.faved_seq ?: 0L

                val isFavorited = when (fav) {
                    true -> favEntry != null
                    false -> favEntry == null
                    else -> true
                }

                includeType && !excludeType && isFavorited && regularSearch
            }
        }

        val sorted = when (sort.first) {
            "pop" -> if (sort.second == "desc") mangas.sortedByDescending { it.favorited } else mangas.sortedBy { it.favorited }
            "tit" -> if (sort.second == "desc") mangas.sortedByDescending { it.name.lowercase() } else mangas.sortedBy { it.name.lowercase() }
            "new" -> if (sort.second == "desc") mangas.sortedByDescending { it.id.toLongOrNull() ?: 0L } else mangas.sortedBy { it.id.toLongOrNull() ?: 0L }
            "fav" -> {
                if (fav != true) throw Exception("Please check 'Favorites Only' Filter")
                if (sort.second == "desc") mangas.sortedByDescending { it.fav } else mangas.sortedBy { it.fav }
            }
            else -> if (sort.second == "desc") mangas.sortedByDescending { it.updatedDate } else mangas.sortedBy { it.updatedDate }
        }

        val startIndex = (page - 1) * PAGE_CREATORS_LIMIT
        val endIndex = minOf(startIndex + PAGE_CREATORS_LIMIT, sorted.size)

        if (startIndex >= sorted.size) {
            return MangasPage(emptyList(), false)
        }

        val pageItems = sorted.subList(startIndex, endIndex)
        val final = pageItems.map { it.toSManga(baseUrl) }
        val hasNextPage = endIndex < sorted.size

        return MangasPage(final, hasNextPage)
    }

    private suspend fun searchPosts(page: Int, query: String, filters: FilterList, popular: Boolean): MangasPage {
        val urlBuilder = "$baseUrl/posts${if (popular) "/popular" else ""}".toHttpUrl().newBuilder()

        if (query.isNotBlank()) urlBuilder.addQueryParameter("q", query.trim())

        val customTag = filters.firstInstanceOrNull<CustomTagFilter>()?.state?.trim().orEmpty()
        val tag = customTag.ifEmpty { filters.firstInstanceOrNull<TagFilter>()?.getValue().orEmpty() }
        if (tag.isNotEmpty()) urlBuilder.addQueryParameter("tag", tag)

        when (filters.firstInstanceOrNull<AiFilter>()?.state ?: 0) {
            1 -> urlBuilder.addQueryParameter("hide", "ai")
            2 -> urlBuilder.addQueryParameter("only", "ai")
        }

        if (popular) {
            val period = filters.firstInstanceOrNull<PeriodFilter>()?.getValue() ?: "recent"
            urlBuilder.addQueryParameter("period", period)
        }

        val offset = (page - 1) * PAGE_POST_LIMIT
        if (offset > 0) urlBuilder.addQueryParameter("o", offset.toString())

        val document = apiClient.get(urlBuilder.build()).asJsoup()

        return MangasPage(document.toPostMangas(), document.selectFirst(".paginator a.next") != null)
    }

    private fun Document.toPostMangas(): List<SManga> = select("article.post-card").map { card ->
        val service = card.attr("data-service")
        val id = card.attr("data-id")
        SManga.create().apply {
            url = "/$service/user/${card.attr("data-user")}/post/$id"
            title = card.selectFirst(".post-card__header")?.text().orEmpty().ifBlank { "Post $id" }
            thumbnail_url = card.selectFirst("img.post-card__image")?.absUrl("src")
            author = service.serviceName()
        }
    }.distinctBy { it.url }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        val segments = url.pathSegments
        if (segments.size < 3 || segments[1] != "user") {
            return null
        }

        val service = segments[0]
        val creatorId = segments[2]

        if (segments.size >= 5 && segments[3] == "post") {
            val post = apiClient.get("$baseUrl/$apiPath/$service/user/$creatorId/post/${segments[4]}", ensureSuccess = false).use { response ->
                if (!response.isSuccessful) return null
                response.parseAs<PawchivePostDto>()
            }
            return post.toSManga()
        }

        val cache = CacheControl.Builder().maxStale(30.minutes).build()
        val creatorsUrl = "$baseUrl/$apiPath/creators"

        return apiClient.get(creatorsUrl, cache, ensureSuccess = false).use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val allCreators = response.parseAs<List<PawchiveCreatorDto>>()
            val creator = allCreators.find { it.id == creatorId && it.service == service } ?: return null
            creator.toSManga(baseUrl)
        }
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (manga.url.contains("/post/")) {
            return fetchPostUpdate(manga, chapters, fetchDetails, fetchChapters)
        }

        val updatedChapters = if (fetchChapters) {
            val prefMaxPost = preferences.getString(POST_PAGES_PREF, POST_PAGES_DEFAULT)!!
                .toInt().coerceAtMost(POST_PAGES_MAX) * PAGE_POST_LIMIT

            val datePref = preferences.getString(POST_DATE_PREF, POST_DATE_PUBLISHED)!!

            var offset = 0
            var hasNextPage = true
            val result = ArrayList<SChapter>()

            while (offset < prefMaxPost && hasNextPage) {
                val url = "$baseUrl/$apiPath${manga.url}/posts?o=$offset"

                val page: List<PawchivePostDto> = apiClient.get(url).parseAs()

                page.forEach { post ->
                    if (post.images.isNotEmpty()) {
                        result.add(post.toSChapter(manga.author, datePref))
                    }
                }

                offset += PAGE_POST_LIMIT
                hasNextPage = page.size == PAGE_POST_LIMIT
            }

            // Every post is its own chapter, so it needs a unique chapter number. Otherwise the
            // app's "Skip duplicate chapters" setting groups them all together and only one
            // chapter remains (breaking reader navigation). Posts arrive newest-first, so the
            // newest gets the highest number.
            result.forEachIndexed { index, chapter ->
                chapter.chapter_number = (result.size - index).toFloat()
            }
            result
        } else {
            chapters
        }

        val details = if (fetchDetails) {
            val tags = fetchTags(manga.url)
            SManga.create().apply {
                url = manga.url
                title = manga.title
                author = manga.author
                thumbnail_url = manga.thumbnail_url
                description = buildString {
                    append(PROMPT)
                    if (tags.isNotEmpty()) {
                        append("\n\nTags: ")
                        append(tags.joinToString(", ") { "${it.tag} (${it.post_count})" })
                    }
                }
                genre = tags.joinToString(", ") { it.tag }
                initialized = true
            }
        } else {
            manga
        }

        return SMangaUpdate(details, updatedChapters)
    }

    private suspend fun fetchPostUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val post: PawchivePostDto = apiClient.get("$baseUrl/$apiPath${manga.url}").parseAs()
        val creatorPath = manga.url.substringBefore("/post/")
        val datePref = preferences.getString(POST_DATE_PREF, POST_DATE_PUBLISHED)!!

        val details = if (fetchDetails) {
            val creatorName = fetchCreatorName(creatorPath)
            val tags = fetchTags(creatorPath)
            SManga.create().apply {
                url = manga.url
                title = post.title.ifBlank { manga.title }
                author = creatorName ?: post.service.serviceName()
                thumbnail_url = manga.thumbnail_url ?: post.images.firstOrNull()?.let { "$imgUrl/thumbnail/$dataPath$it" }
                description = buildString {
                    post.content?.htmlToText()?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
                    append("Creator: ").append(creatorName ?: post.user).append(" (").append(post.service.serviceName()).append(")\n")
                    append("Creator profile: ").append(baseUrl).append(creatorPath).append('\n')
                    append("Paste that link in the search bar to open the full creator.")
                    if (tags.isNotEmpty()) {
                        append("\n\nCreator tags: ")
                        append(tags.joinToString(", ") { "${it.tag} (${it.post_count})" })
                    }
                }
                genre = tags.joinToString(", ") { it.tag }
                initialized = true
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(post.toSChapter(post.service.serviceName(), datePref))
        } else {
            chapters
        }

        return SMangaUpdate(details, updatedChapters)
    }

    private suspend fun fetchTags(creatorPath: String): List<PawchiveTagDto> {
        val url = "$baseUrl/$apiPath${creatorPath.substringBefore("/post/")}/tags"
        return apiClient.get(url, ensureSuccess = false).use { response ->
            if (response.isSuccessful) {
                response.parseAs<List<PawchiveTagDto>>().sortedByDescending { it.post_count }.take(TAGS_IN_DETAILS)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun fetchCreatorName(creatorPath: String): String? {
        val url = "$baseUrl/$apiPath$creatorPath/profile"
        return apiClient.get(url, ensureSuccess = false).use { response ->
            if (response.isSuccessful) response.parseAs<PawchiveProfileDto>().name else null
        }
    }

    private fun PawchivePostDto.toSManga() = SManga.create().apply {
        url = postUrl
        title = this@toSManga.title.ifBlank { "Post $id" }
        author = service.serviceName()
        thumbnail_url = images.firstOrNull()?.let { "$imgUrl/thumbnail/$dataPath$it" }
    }

    private fun String.htmlToText(): String {
        val doc = Jsoup.parse(this)
        doc.select("br").append("\\n")
        doc.select("p").prepend("\\n\\n")
        return doc.text().replace("\\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/$apiPath${chapter.url}"

        apiClient.get(url).use { response ->
            val postData: PawchivePostDto = response.parseAs()

            if (postData.images.isEmpty()) {
                throw Exception("This post has no image files (videos and other attachments are not supported)")
            }

            val useLowRes = preferences.getBoolean(USE_LOW_RES_IMG, false)

            return postData.images.mapIndexed { i, path ->
                val originalUrl = "$fileUrl/$dataPath$path"

                val finalImageUrl = if (useLowRes) originalUrl.toThumbnailUrl() else originalUrl

                Page(i, imageUrl = finalImageUrl)
            }
        }
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = apiClient.get("$baseUrl/posts/tags").asJsoup()
        val tags = document.select("#tag-container article a")
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull()?.queryParameter("tag") }
            .take(TAG_FILTER_LIMIT)
        check(tags.isNotEmpty()) { "No tags found" }
        return buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } }
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val tags = (data as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        return getDefaultFilterList(tags)
    }

    // ============================= Utilities =============================

    private fun String.toThumbnailUrl(): String {
        val pathIndex = this.indexOf('/', 8) // Skips the `https://`
        return buildString {
            append(imgUrl)
            append("/thumbnail")
            append(this@toThumbnailUrl.substring(pathIndex))
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = POST_PAGES_PREF
            title = "Maximum posts to load"
            summary = "Loading more posts costs more time and network traffic.\nCurrently: %s"
            entryValues = Array(POST_PAGES_MAX) { (it + 1).toString() }
            entries = Array(POST_PAGES_MAX) { "${(it + 1)} pages (${(it + 1) * PAGE_POST_LIMIT} posts)" }
            setDefaultValue(POST_PAGES_DEFAULT)
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = POST_DATE_PREF
            title = "Preferred Post Date"
            summary = "Choose which date to use for chapters.\nCurrently: %s"
            entries = arrayOf("Added Date", "Published Date", "Edited Date")
            entryValues = arrayOf(POST_DATE_ADDED, POST_DATE_PUBLISHED, POST_DATE_EDITED)
            setDefaultValue(POST_DATE_PUBLISHED)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = USE_LOW_RES_IMG
            title = "Use low resolution images"
            summary = "Reduce load time significantly. When turning off, clear chapter cache to remove cached low resolution images."
            setDefaultValue(false)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = FALLBACK_LOW_RES_IMG
            title = "Fallback to low resolution images"
            summary =
                "If a full size image fails to load (e.g. 404 Not Found), automatically try loading the low resolution thumbnail instead."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    companion object {
        private const val PAGE_POST_LIMIT = 50
        private const val PAGE_CREATORS_LIMIT = 50
        private const val TAG_FILTER_LIMIT = 150
        private const val TAGS_IN_DETAILS = 15
        const val PROMPT = "You can change how many posts to load in the extension preferences."

        private const val POST_PAGES_PREF = "POST_PAGES"
        private const val POST_PAGES_DEFAULT = "1"
        private const val POST_PAGES_MAX = 75

        private const val USE_LOW_RES_IMG = "USE_LOW_RES_IMG"
        private const val FALLBACK_LOW_RES_IMG = "FALLBACK_LOW_RES_IMG"

        private const val POST_DATE_PREF = "POST_DATE_PREF"
        private const val POST_DATE_ADDED = "added"
        private const val POST_DATE_PUBLISHED = "published"
        private const val POST_DATE_EDITED = "edited"
    }
}
