package ru.proshik.pochitushki.model

import java.time.LocalDateTime

data class PostData(
    val id: Long,
    val title: String?,
    val url: String,
    val userId: Long,
    val tags: List<String>?,
    val isFavorite: Boolean = false,
    val createdDate: LocalDateTime,
    val updatedDate: LocalDateTime
)

data class PostStoreData(
    val title: String?,
    val url: String,
    val userId: Long,
    val tags: List<String>? = null,
    val createdDate: LocalDateTime? = null,
    val updatedDate: LocalDateTime? = null
)

data class PostStoreDataWithId(
    val id: Long,
    val title: String?,
    val url: String,
    val userId: Long,
    val tags: List<String>? = null,
    val createdDate: LocalDateTime? = null,
    val updatedDate: LocalDateTime? = null
)

data class UserToPostData(
    val postId: Long,
    val createdData: LocalDateTime
)

enum class PostType(val value: String) {
    UNREAD("unread"),
    ARCHIVE("archive"),
    FAVORITES("favorites");

    companion object {
        private val stringToType = PostType.entries.associateBy { it.name }

        fun from(name: String): PostType = stringToType[name] ?: error("unknown post status: $name")
    }
}

fun PostStoreData.toPostStoreDataWithId(id: Long) =
    PostStoreDataWithId(
        id = id,
        title = title,
        url = url,
        userId = userId,
        tags = tags,
        createdDate = createdDate,
        updatedDate = updatedDate
    )
