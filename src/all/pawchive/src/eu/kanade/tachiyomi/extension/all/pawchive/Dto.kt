package eu.kanade.tachiyomi.extension.all.pawchive

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Serializable
class PawchiveFavoritesDto(
    val id: String,
    val faved_seq: Long,
)

@Serializable
class PawchiveCreatorDto(
    val id: String,
    val name: String,
    val service: String,
    private val updated: JsonPrimitive,
    val favorited: Int = -1,
) {
    @Transient
    var fav: Long = 0L

    val updatedDate get() = when {
        updated.isString -> Instant.parseOrNull(updated.content)?.toEpochMilliseconds() ?: 0L
        else -> (updated.double * 1000).toLong()
    }

    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/$service/user/$id"
        title = name
        author = service.serviceName()
        thumbnail_url = "$baseUrl/icons/$service/$id"
        description = Pawchive.PROMPT
        initialized = true
    }

    companion object {
        fun String.serviceName() = when (this) {
            "fanbox" -> "Pixiv Fanbox"
            else -> replaceFirstChar { it.uppercase() }
        }
    }
}

@Serializable
class PawchivePostDto(
    val id: String,
    val service: String,
    val user: String,
    val title: String,
    private val tags: JsonElement? = null,
    private val added: String? = null,
    private val published: String? = null,
    private val edited: String? = null,
    private val file: PawchiveFileDto? = null,
    private val attachments: List<PawchiveFileDto> = emptyList(),
) {
    /** Tags are optional and parsed leniently so an unexpected shape never breaks chapter loading. */
    val tagList: List<String>
        get() = (tags as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    val images: List<String>
        get() = (listOfNotNull(file) + attachments)
            .filter { it.path.isNotBlank() && it.path.substringAfterLast('.').lowercase() in validImageExtensions }
            .distinctBy { it.path }
            .map { it.toString() }

    fun toSChapter(serviceName: String?, datePref: String) = SChapter.create().apply {
        val dateStr = when (datePref) {
            "added" -> added ?: published ?: edited
            "edited" -> edited ?: published ?: added
            else -> published ?: added ?: edited // Default to published
        }

        val postDate = dateStr?.let {
            val dateWithTz = if (it.endsWith("Z") || it.contains("+")) {
                it
            } else {
                it + if (serviceName == "Pixiv Fanbox") "+09:00" else "Z"
            }
            Instant.parseOrNull(dateWithTz)?.toEpochMilliseconds()
        } ?: 0L

        url = "/$service/user/$user/post/$id"
        date_upload = postDate

        name = title.ifBlank {
            val postDateString = if (postDate != 0L && dateStr != null) {
                // Strips any unexpected timezone markers and recreates "yyyy-MM-dd 'at' HH:mm:ss" natively
                dateStr.substringBefore("+").substringBefore("Z").replace("T", " at ")
            } else {
                "unknown date"
            }
            "Post from $postDateString"
        }
    }

    companion object {
        private val validImageExtensions = setOf("png", "jpg", "gif", "jpeg", "webp")
    }
}

@Serializable
class PawchiveFileDto(val path: String = "") {
    override fun toString() = path
}

/** A post card scraped from /posts, /posts?tag=... or /posts/popular. */
class PawchivePostCardDto(
    val service: String,
    val user: String,
    val id: String,
    val title: String,
    val thumbnail: String?,
    val info: String,
) {
    companion object {
        fun from(element: Element): PawchivePostCardDto? {
            val service = element.attr("data-service").ifBlank { return null }
            val user = element.attr("data-user").ifBlank { return null }
            val id = element.attr("data-id").ifBlank { return null }
            val title = element.selectFirst(".post-card__header")?.text().orEmpty()
            val thumbnail = element.selectFirst("img.post-card__image")
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
            val info = element.selectFirst("footer")?.text().orEmpty()
            return PawchivePostCardDto(service, user, id, title, thumbnail, info)
        }
    }
}
