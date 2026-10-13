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

class TypeFilter(name: String, vals: List<String>) : Filter.Group<TriFilter>(name, vals.map { TriFilter(it, it.lowercase()) })

class FavoritesFilter : Filter.Group<TriFilter>("Favorites", listOf(TriFilter("Favorites Only", "fav")))

class TriFilter(name: String, val value: String) : Filter.TriState(name)

class SortFilter(name: String, selection: Selection, private val vals: List<Pair<String, String>>) : Filter.Sort(name, vals.map { it.first }.toTypedArray(), selection) {
    fun getValue() = vals[state!!.index].second
}

class ModeFilter : Filter.Select<String>("Browse", arrayOf("Creators", "Posts", "Popular posts"))

class TagFilter(tags: List<String>) : Filter.Select<String>("Tag", arrayOf("Any") + tags.toTypedArray()) {
    fun getValue(): String? = if (state == 0) null else values[state]
}

class CustomTagFilter : Filter.Text("Custom tag (overrides list)")

class AiFilter : Filter.Select<String>("AI-generated", arrayOf("Show", "Hide", "Only"))

class PeriodFilter : Filter.Select<String>("Popular period", arrayOf("Recent", "Day", "Week", "Month")) {
    fun getValue(): String = values[state].lowercase()
}

fun getLatestUpdatesFilterList(): FilterList = FilterList(
    SortFilter("Sort by", Filter.Sort.Selection(2, false), getSortsList),
    TypeFilter("Types", getTypes),
    FavoritesFilter(),
)

fun getDefaultFilterList(tags: List<String> = emptyList()): FilterList = FilterList(
    ModeFilter(),
    Filter.Separator(),
    Filter.Header("Creators mode"),
    SortFilter("Sort by", Filter.Sort.Selection(0, false), getSortsList),
    TypeFilter("Types", getTypes),
    FavoritesFilter(),
    Filter.Separator(),
    Filter.Header("Posts / Popular posts mode"),
    TagFilter(tags),
    CustomTagFilter(),
    AiFilter(),
    Filter.Separator(),
    Filter.Header("Popular posts mode"),
    PeriodFilter(),
)
