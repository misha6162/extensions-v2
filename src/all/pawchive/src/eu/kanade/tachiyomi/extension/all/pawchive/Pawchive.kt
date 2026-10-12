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
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
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
        var mode = MODE_CREATORS
        var tag = ""
        var tagPick = ""
        var period = "day"
        var date = ""
        var ai = ""
        var similar = ""

        filters.forEach { filter ->
            when (filter) {
                is SearchModeFilter -> mode = filter.state
                is TagFilter -> tag = filter.state.trim()
                is TagListFilter -> tagPick = filter.getValue()
                is PopularPeriodFilter -> period = filter.getValue()
                is PopularDateFilter -> date = filter.state.trim()
                is AiFilter -> ai = filter.getValue()
                is SimilarFilter -> similar = filter.state.trim()
                else -> {}
            }
        }

        val finalTag = tag.ifEmpty { tagPick }
        if (mode == MODE_CREATORS && finalTag.isNotEmpty()) {
            mode = MODE_POSTS
        } else if (mode == MODE_CREATORS && similar.isNotEmpty()) {
            mode = MODE_SIMILAR
        }

        return when (mode) {
            MODE_POSTS -> searchPosts(page, query, finalTag, ai)
            MODE_POPULAR -> popularPosts(page, query, period, date)
            MODE_SIMILAR -> searchSimilar(similar.ifEmpty { query })
            else -> searchCreators(page, query, filters)
        }
    }

    // ------------------------------ Creators ------------------------------

    private suspend fun searchCreators(page: Int, query: String, filters: FilterList): MangasPage {
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

            val creatorsDeferred = async(Dispatchers.IO) { fetchCreators() }

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

    // ------------------------------- Posts --------------------------------

    private suspend fun searchPosts(page: Int, query: String, tag: String, ai: String): MangasPage {
        val q = query.trim()
        if (q.isNotEmpty() && q.length < 2) {
            throw Exception("Post search needs at least 2 characters")
        }

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("posts")
            .apply {
                if (q.isNotEmpty()) addQueryParameter("q", q)
                if (tag.isNotEmpty()) addQueryParameter("tag", tag)
                if (ai.isNotEmpty()) addQueryParameter(ai, "ai")
                if (page > 1) addQueryParameter("o", ((page - 1) * PAGE_POST_LIMIT).toString())
            }
            .build()

        return fetchPostCards(url.toString(), null)
    }

    private suspend fun popularPosts(page: Int, query: String, period: String, date: String): MangasPage {
        if (date.isNotEmpty() && !DATE_REGEX.matches(date)) {
            throw Exception("Invalid date. Use the format YYYY-MM-DD")
        }

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("posts/popular")
            .addQueryParameter("period", period)
            .apply {
                if (date.isNotEmpty()) addQueryParameter("date", date)
                if (page > 1) addQueryParameter("o", ((page - 1) * PAGE_POST_LIMIT).toString())
            }
            .build()

        // The popular page has no text search, so the query filters the loaded titles.
        return fetchPostCards(url.toString(), query.trim().ifEmpty { null })
    }

    private suspend fun fetchPostCards(url: String, titleFilter: String?): MangasPage {
        val (document, creators) = coroutineScope {
            val documentDeferred = async(Dispatchers.IO) { apiClient.get(url).asJsoup() }
            val creatorsDeferred = async(Dispatchers.IO) {
                // Only used to resolve creator names; posts still show if this fails.
                runCatching { fetchCreators() }.getOrDefault(emptyList())
            }
            documentDeferred.await() to creatorsDeferred.await()
        }

        val creatorMap = creators.associateBy { "${it.service}/${it.id}" }

        val mangas = document.select("article.post-card")
            .mapNotNull { PawchivePostCardDto.from(it) }
            .filter { titleFilter == null || it.title.contains(titleFilter, true) }
            .distinctBy { "${it.service}/${it.user}/${it.id}" }
            .map { card ->
                val creator = creatorMap["${card.service}/${card.user}"]
                SManga.create().apply {
                    this.url = "/${card.service}/user/${card.user}/post/${card.id}"
                    title = card.title.ifBlank { "Post ${card.id}" }
                    author = creator?.name ?: "${card.service.serviceName()} ${card.user}"
                    thumbnail_url = card.thumbnail
                    description = buildString {
                        append(card.service.serviceName())
                        if (card.info.isNotBlank()) append("\n").append(card.info)
                    }
                    initialized = true
                }
            }

        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    private suspend fun fetchCreators(): List<PawchiveCreatorDto> {
        val url = "$baseUrl/$apiPath/creators"
        val cacheControl = CacheControl.Builder().maxStale(30.minutes).build()

        return apiClient.get(url, cacheControl).use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP error ${response.code}")
            }
            response.parseAs<List<PawchiveCreatorDto>>()
        }
    }

    // --------------------------- Similar artists ---------------------------

    private suspend fun searchSimilar(source: String): MangasPage {
        val match = SIMILAR_REGEX.find(source.trim())
            ?: throw Exception("Enter a creator link or service/id (e.g. fanbox/37736420)")
        val service = match.groupValues[1]
        val id = match.groupValues[2]

        val document = apiClient.get("$baseUrl/$service/user/$id/recommended").asJsoup()

        val mangas = document.select("a.user-card").mapNotNull { card ->
            val cardService = card.attr("data-service").ifBlank { return@mapNotNull null }
            val cardId = card.attr("data-id").ifBlank { return@mapNotNull null }
            SManga.create().apply {
                url = "/$cardService/user/$cardId"
                title = card.selectFirst(".user-card__name")?.text().orEmpty().ifBlank { cardId }
                author = cardService.serviceName()
                thumbnail_url = "$baseUrl/icons/$cardService/$cardId"
                description = PROMPT
                initialized = true
            }
        }

        return MangasPage(mangas, false)
    }

    // ------------------------------ Single post ------------------------------

    private suspend fun fetchPostUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val post: PawchivePostDto = apiClient.get("$baseUrl/$apiPath${manga.url}").parseAs()

        if (fetchDetails) {
            val creatorName = runCatching { fetchCreators() }.getOrNull()
                ?.find { it.id == post.user && it.service == post.service }?.name

            // Use the tags from the API when present; otherwise try the post page.
            val tags = post.tagList.filter { it.isNotBlank() }
                .ifEmpty { fetchPostTags(manga.url) }

            manga.title = post.title.ifBlank { manga.title }
            manga.author = creatorName ?: manga.author
            manga.genre = tags.joinToString(", ").ifEmpty { null }
            manga.description = buildString {
                append(post.service.serviceName())
                if (tags.isNotEmpty()) append("\nTags: ").append(tags.joinToString(", "))
                if (post.images.isEmpty()) append("\nNo images in this post (video or other files only).")
            }
            manga.initialized = true
        }

        val updatedChapters = if (fetchChapters) {
            val datePref = preferences.getString(POST_DATE_PREF, POST_DATE_PUBLISHED)!!
            listOf(
                post.toSChapter(post.service.serviceName(), datePref).apply {
                    chapter_number = 1f
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(manga, updatedChapters)
    }

    private suspend fun fetchPostTags(postPath: String): List<String> = runCatching {
        apiClient.get("$baseUrl$postPath").asJsoup()
            .select("a[href^=\"/posts?tag=\"]")
            .map { it.text().trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())

    // ------------------------------- By URL -------------------------------

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        val segments = url.pathSegments
        val service: String?
        val id: String

        when {
            // Legacy form: /creators/{id}
            segments.firstOrNull() == "creators" && segments.size >= 2 -> {
                service = null
                id = segments[1]
            }
            // Post form: /{service}/user/{id}/post/{postId}
            segments.size >= 5 && segments[1] == "user" && segments[3] == "post" -> {
                val postPath = "/${segments[0]}/user/${segments[2]}/post/${segments[4]}"
                val post: PawchivePostDto = runCatching {
                    apiClient.get("$baseUrl/$apiPath$postPath").parseAs<PawchivePostDto>()
                }.getOrNull() ?: return null
                return SManga.create().apply {
                    this.url = postPath
                    title = post.title.ifBlank { "Post ${post.id}" }
                    author = post.service.serviceName()
                    description = post.service.serviceName()
                    initialized = false
                }
            }
            // Creator form: /{service}/user/{id}
            segments.size >= 3 && segments[1] == "user" -> {
                service = segments[0]
                id = segments[2]
            }
            else -> return null
        }

        if (id.isBlank()) {
            return null
        }

        val creator = runCatching { fetchCreators() }.getOrNull()
            ?.find { it.id == id && (service == null || it.service == service) }
            ?: return null

        return creator.toSManga(baseUrl)
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

        return SMangaUpdate(manga, updatedChapters)
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/$apiPath${chapter.url}"

        apiClient.get(url).use { response ->
            val postData: PawchivePostDto = response.parseAs()

            if (postData.images.isEmpty()) {
                throw Exception("This post has no images (video or other files only)")
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

    override fun getFilterList(data: JsonElement?): FilterList = getDefaultFilterList()

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
        private val SIMILAR_REGEX = Regex("""(?:https?://[^/]+/)?([a-z]+)/(?:user/)?([^/?#\s]+)""")
        private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
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
