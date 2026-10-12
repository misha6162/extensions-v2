package eu.kanade.tachiyomi.extension.all.pawchive

import eu.kanade.tachiyomi.extension.all.pawchive.PawchiveCreatorDto.Companion.serviceName
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlin.time.Instant

@Serializable
class PawchiveFavoritesDto(
    val id: String,
    val faved_seq: Long,
    val service: String? = null,
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
    private val added: String? = null,
    private val published: String? = null,
    private val edited: String? = null,
    private val file: PawchiveFileDto? = null,
    private val attachments: List<PawchiveFileDto> = emptyList(),
    private val tags: JsonElement? = null,
) {
    private val allFiles
        get() = (listOfNotNull(file) + attachments)
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }

    val images: List<String>
        get() = allFiles.filter { it.extension in validImageExtensions }.map { it.path }

    val videoCount: Int
        get() = allFiles.count { it.extension in validVideoExtensions }

    // The field is not documented; accept both a JSON array and a comma separated string.
    val tagList: List<String>
        get() = when (tags) {
            is JsonArray -> tags.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            is JsonPrimitive -> tags.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            else -> emptyList()
        }

    val displayTitle get() = title.ifBlank { "Post $id" }

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

        scanlator = buildList {
            add("${images.size} img")
            if (videoCount > 0) add("$videoCount vid")
            tagList.take(3).forEach { add("#$it") }
        }.joinToString(" · ")

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

    /** A single post as its own entry, used by post search, URL opening and related posts. */
    fun toSManga(baseUrl: String, thumbBase: String, creator: PawchiveCreatorDto?) = SManga.create().apply {
        val creatorName = creator?.name ?: user
        url = "/$service/user/$user/post/$id"
        title = displayTitle
        author = creatorName
        artist = service.serviceName()
        genre = tagList.joinToString(", ") { "#$it" }.ifEmpty { null }
        status = SManga.COMPLETED
        thumbnail_url = images.firstOrNull()?.let { "$thumbBase/thumbnail/data$it" }
        description = buildString {
            append("Creator: $creatorName (${service.serviceName()})\n")
            append("Creator page: $baseUrl/$service/user/$user\n")
            append("Images: ${images.size}")
            if (videoCount > 0) append(" · Videos (not viewable here): $videoCount")
        }
        initialized = true
    }

    companion object {
        private val validImageExtensions = setOf("png", "jpg", "gif", "jpeg", "webp")
        private val validVideoExtensions = setOf("mp4", "webm", "mov", "m4v", "mkv")
    }
}

@Serializable
class PawchiveFileDto(val path: String = "") {
    val extension get() = path.substringAfterLast('.').lowercase()

    override fun toString() = path
}
