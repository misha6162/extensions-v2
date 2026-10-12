package eu.kanade.tachiyomi.extension.all.pawchive

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

val getTypes = listOf("Patreon", "Pixiv Fanbox")

val getSortsList: List<Pair<String, String>> = listOf(
    Pair("Popularity", "pop"),
    Pair("Date Indexed", "new"),
    Pair("Date Updated", "lat"),
    Pair("Alphabetical Order", "tit"),
    Pair("Service", "serv"),
    Pair("Date Favorited", "fav"),
)

const val MODE_CREATORS = 0
const val MODE_POSTS = 1
const val MODE_POPULAR = 2
const val MODE_SIMILAR = 3

val getSearchModes = arrayOf("Creators", "Posts", "Popular posts", "Similar artists")

val getPopularPeriods: List<Pair<String, String>> = listOf(
    Pair("Day", "day"),
    Pair("Week", "week"),
    Pair("Month", "month"),
)

val getAiOptions: List<Pair<String, String>> = listOf(
    Pair("Show", ""),
    Pair("Hide", "hide"),
    Pair("Only", "only"),
)

// Most used tags on pawchive.pw/posts/tags. Any other tag can be typed in the text filter.
val getCommonTags = listOf(
    "nsfw", "wip", "commission", "sketch", "comic", "poll", "r-18", "art", "animation", "anime",
    "fanart", "oc", "nude", "sfw", "furry", "early access", "female", "update", "video",
    "illustration", "pokemon", "preview", "sexy", "feet", "hentai", "futanari", "giantess",
    "genshin impact", "ai", "transformation", "3d", "vore", "anthro", "vtuber", "naruto",
    "one piece", "milf", "cosplay", "bondage", "gay", "lingerie", "オリジナル", "ブルーアーカイブ",
)

class TypeFilter(name: String, vals: List<String>) : Filter.Group<TriFilter>(name, vals.map { TriFilter(it, it.lowercase()) })

class FavoritesFilter : Filter.Group<TriFilter>("Favorites", listOf(TriFilter("Favorites Only", "fav")))

class TriFilter(name: String, val value: String) : Filter.TriState(name)

class SortFilter(name: String, selection: Selection, private val vals: List<Pair<String, String>>) : Filter.Sort(name, vals.map { it.first }.toTypedArray(), selection) {
    fun getValue() = vals[state!!.index].second
}

class SearchModeFilter : Filter.Select<String>("Search in", getSearchModes, MODE_CREATORS)

class TagFilter : Filter.Text("Tag (exact name)")

class SimilarFilter : Filter.Text("Similar artists: creator link or service/id")

class TagListFilter : Filter.Select<String>("Common tags", (listOf("None") + getCommonTags).toTypedArray()) {
    fun getValue() = if (state == 0) "" else getCommonTags[state - 1]
}

class PopularPeriodFilter : Filter.Select<String>("Popular: period", getPopularPeriods.map { it.first }.toTypedArray()) {
    fun getValue() = getPopularPeriods[state].second
}

class PopularDateFilter : Filter.Text("Popular: date (YYYY-MM-DD, optional)")

class AiFilter : Filter.Select<String>("AI-generated posts", getAiOptions.map { it.first }.toTypedArray()) {
    fun getValue() = getAiOptions[state].second
}

private fun postFilters(): List<Filter<*>> = listOf(
    SearchModeFilter(),
    Filter.Header("Posts / Popular posts: each result is a single post. A tag switches to Posts automatically."),
    Filter.Header("Similar artists: paste a creator link or e.g. fanbox/37736420 here or in the search box."),
    TagFilter(),
    TagListFilter(),
    SimilarFilter(),
    AiFilter(),
    PopularPeriodFilter(),
    PopularDateFilter(),
    Filter.Separator(),
)

fun getLatestUpdatesFilterList(): FilterList = FilterList(
    SortFilter("Sort by", Filter.Sort.Selection(2, false), getSortsList),
    TypeFilter("Types", getTypes),
    FavoritesFilter(),
)

fun getDefaultFilterList(): FilterList = FilterList(
    postFilters() + listOf<Filter<*>>(
        SortFilter("Sort by", Filter.Sort.Selection(0, false), getSortsList),
        TypeFilter("Types", getTypes),
        FavoritesFilter(),
    ),
)
