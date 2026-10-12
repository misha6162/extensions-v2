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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.io.use
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.add as addJson

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
        var sort: Pair<String, String> = "" to ""
        val typeIncluded = mutableListOf<String>()
        val typeExcluded = mutableListOf<String>()
        var fav: Boolean? = null
        var postsMode = false
        var aiMode = 0
        var popularPeriod = 0
        val tags = linkedSetOf<String>()

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
                is SearchModeFilter -> postsMode = filter.state == 1
                is AiFilter -> aiMode = filter.state
                is PopularPeriodFilter -> popularPeriod = filter.state
                is TagSelectFilter -> filter.value?.let(tags::add)
                is TagTextFilter -> filter.state.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(tags::add)
                else -> {}
            }
        }

        // Profile genre chips are shown as "#tag", so tapping one lands here.
        var text = query.trim()
        if (text.startsWith("#")) {
            text.removePrefix("#").trim().takeIf { it.isNotEmpty() }?.let(tags::add)
            text = ""
        }

        if (postsMode || tags.isNotEmpty() || popularPeriod > 0) {
            return searchPosts(page, text, tags, aiMode, popularPeriod)
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
                val regularSearch = creator.name.contains(text, true)

                // Ids are only unique per service, so match both when the API tells us the service.
                val favEntry = favorites.find { f -> f.id == creator.id && (f.service == null || f.service == creator.service) }
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
            "serv" -> {
                val byService = compareBy<PawchiveCreatorDto>({ it.service }, { it.name.lowercase() })
                if (sort.second == "desc") mangas.sortedWith(byService.reversed()) else mangas.sortedWith(byService)
            }
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

    // Posts are scraped from the HTML listing: it is the only place where the `q`, `tag`,
    // `hide`/`only` (AI filter) and popular parameters are known to work.
    private suspend fun searchPosts(page: Int, query: String, tags: Collection<String>, ai: Int, period: Int): MangasPage {
        val offset = (page - 1) * PAGE_POST_LIMIT
        val base = if (period > 0) "$baseUrl/posts/popular" else "$baseUrl/posts"

        val url = base.toHttpUrl().newBuilder().apply {
            if (period > 0) addQueryParameter("period", POPULAR_PERIODS[period])
            // The site rejects queries shorter than 2 characters.
            if (query.length >= 2) addQueryParameter("q", query)
            tags.forEach { addQueryParameter("tag", it) }
            when (ai) {
                1 -> addQueryParameter("hide", "ai")
                2 -> addQueryParameter("only", "ai")
            }
            if (offset > 0) addQueryParameter("o", offset.toString())
        }.build()

        val document = apiClient.get(url.toString()).asJsoup()

        val mangas = document.select("article.post-card").mapNotNull { card ->
            val link = card.selectFirst("a.image-link") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = card.selectFirst(".post-card__header")?.text()?.takeIf { it.isNotBlank() }
                    ?: "Post ${card.attr("data-id")}"
                thumbnail_url = card.selectFirst("img.post-card__image")?.absUrl("src")
                artist = card.attr("data-service").serviceName()
                status = SManga.COMPLETED
            }
        }

        return MangasPage(mangas, document.selectFirst("a.next") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        // /{service}/user/{id}[/post/{postId}]
        val segments = url.pathSegments
        if (segments.size < 3 || segments[1] != "user" || segments[2].isBlank()) {
            return null
        }
        val service = segments[0]
        val userId = segments[2]

        if (segments.size >= 5 && segments[3] == "post") {
            val post = apiClient.get("$baseUrl/$apiPath/$service/user/$userId/post/${segments[4]}", ensureSuccess = false).use { response ->
                if (!response.isSuccessful) return null
                response.parseAs<PawchivePostDto>()
            }
            return post.toSManga(baseUrl, imgUrl, findCreator(service, userId))
        }

        return findCreator(service, userId)?.toSManga(baseUrl)
    }

    private suspend fun fetchCreators(): List<PawchiveCreatorDto> {
        val cacheControl = CacheControl.Builder().maxStale(30.minutes).build()

        return apiClient.get("$baseUrl/$apiPath/creators", cacheControl).use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP error ${response.code}")
            }
            response.parseAs<List<PawchiveCreatorDto>>()
        }
    }

    private suspend fun findCreator(service: String, id: String): PawchiveCreatorDto? = fetchCreators().find { it.id == id && it.service == service }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = if (manga.url.contains("/post/")) {
        fetchPostUpdate(manga, chapters, fetchDetails, fetchChapters)
    } else {
        fetchCreatorUpdate(manga, chapters, fetchDetails, fetchChapters)
    }

    private suspend fun fetchPostUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val post: PawchivePostDto = apiClient.get("$baseUrl/$apiPath${manga.url}").parseAs()

        val details = if (fetchDetails) {
            post.toSManga(baseUrl, imgUrl, findCreator(post.service, post.user))
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val datePref = preferences.getString(POST_DATE_PREF, POST_DATE_PUBLISHED)!!
            listOf(
                post.toSChapter(post.service.serviceName(), datePref).apply { chapter_number = 1f },
            )
        } else {
            chapters
        }

        return SMangaUpdate(details, updatedChapters)
    }

    private suspend fun fetchCreatorUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Tags come from the posts listing, so details need at least the first page.
        val maxPosts = if (fetchChapters) {
            preferences.getString(POST_PAGES_PREF, POST_PAGES_DEFAULT)!!
                .toInt().coerceAtMost(POST_PAGES_MAX) * PAGE_POST_LIMIT
        } else {
            PAGE_POST_LIMIT
        }

        val posts = ArrayList<PawchivePostDto>()
        var offset = 0
        var hasNextPage = true

        while (offset < maxPosts && hasNextPage) {
            val url = "$baseUrl/$apiPath${manga.url}/posts?o=$offset"

            val page: List<PawchivePostDto> = apiClient.get(url).parseAs()
            posts.addAll(page)

            offset += PAGE_POST_LIMIT
            hasNextPage = page.size == PAGE_POST_LIMIT
        }

        val updatedChapters = if (fetchChapters) {
            val datePref = preferences.getString(POST_DATE_PREF, POST_DATE_PUBLISHED)!!

            val result = posts
                .filter { it.images.isNotEmpty() }
                .map { it.toSChapter(manga.author, datePref) }

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

        val details = if (fetchDetails) manga.withTopTags(posts) else manga

        return SMangaUpdate(details, updatedChapters)
    }

    private fun SManga.withTopTags(posts: List<PawchivePostDto>): SManga {
        val top = posts.flatMap { it.tagList }
            .groupingBy { it.lowercase() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(TOP_TAGS)

        if (top.isEmpty()) return this

        return SManga.create().apply {
            url = this@withTopTags.url
            title = this@withTopTags.title
            author = this@withTopTags.author
            thumbnail_url = this@withTopTags.thumbnail_url
            status = this@withTopTags.status
            genre = top.joinToString(", ") { "#${it.key}" }
            description = PROMPT + "\n\nTop tags in the ${posts.size} loaded posts: " +
                top.joinToString(", ") { "${it.key} (${it.value})" }
        }
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    // ============================== Related ==============================

    // From a post: the creator plus their latest posts. Only shown by Komikku.
    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val segments = manga.url.trim('/').split('/')
        if (segments.size < 5 || segments[3] != "post") return emptyList()

        val service = segments[0]
        val userId = segments[2]
        val creator = findCreator(service, userId)

        val posts: List<PawchivePostDto> = apiClient.get("$baseUrl/$apiPath/$service/user/$userId/posts").parseAs()

        return listOfNotNull(creator?.toSManga(baseUrl)) + posts
            .filter { it.id != segments[4] && it.images.isNotEmpty() }
            .take(RELATED_POSTS)
            .map { it.toSManga(baseUrl, imgUrl, creator) }
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/$apiPath${chapter.url}"

        apiClient.get(url).use { response ->
            val postData: PawchivePostDto = response.parseAs()

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

        val names = document.select("#tag-container article a")
            .mapNotNull { it.selectFirst("span")?.text()?.trim()?.takeIf(String::isNotEmpty) }
            .take(TAG_FILTER_LIMIT)

        return buildJsonArray { names.forEach { addJson(it) } }
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
        private const val TOP_TAGS = 25
        private const val RELATED_POSTS = 20
        const val PROMPT = "You can change how many posts to load in the extension preferences."

        private val POPULAR_PERIODS = listOf("", "day", "week", "month")

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
