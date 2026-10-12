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

class SearchModeFilter : Filter.Select<String>("Search in", arrayOf("Creators", "Posts"))

class AiFilter : Filter.Select<String>("AI-generated posts", arrayOf("Show", "Hide", "Only"))

class PopularPeriodFilter : Filter.Select<String>("Popular posts", arrayOf("Off", "Day", "Week", "Month"))

class TagSelectFilter(tags: List<String>) : Filter.Select<String>("Popular tags", (listOf("Any") + tags).toTypedArray()) {
    val value: String? get() = if (state > 0) values[state] else null
}

class TagTextFilter : Filter.Text("Tags (comma separated)")

fun getLatestUpdatesFilterList(): FilterList = FilterList(
    SortFilter("Sort by", Filter.Sort.Selection(2, false), getSortsList),
    TypeFilter("Types", getTypes),
    FavoritesFilter(),
)

fun getDefaultFilterList(tags: List<String> = emptyList()): FilterList = FilterList(
    buildList<Filter<*>> {
        add(SortFilter("Sort by", Filter.Sort.Selection(0, false), getSortsList))
        add(TypeFilter("Types", getTypes))
        add(FavoritesFilter())
        add(Filter.Separator())
        add(Filter.Header("Post search: the options below only apply to posts. Choosing a tag, a popular period or typing '#tag' switches to posts automatically."))
        add(SearchModeFilter())
        add(AiFilter())
        if (tags.isNotEmpty()) add(TagSelectFilter(tags))
        add(TagTextFilter())
        add(PopularPeriodFilter())
    },
)
