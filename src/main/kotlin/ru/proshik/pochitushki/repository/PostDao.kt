package ru.proshik.pochitushki.repository

import java.sql.Array
import org.springframework.dao.support.DataAccessUtils
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostStoreDataWithId
import ru.proshik.pochitushki.model.PostType

@Repository
class PostDao(private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate) {

    @Suppress("UNCHECKED_CAST")
    private val postRowMapper = RowMapper { rs, _ ->
        val array: Array? = rs.getArray("tags")

        val tags = if (array != null) {
            val arr = array.array as kotlin.Array<String>
            arr.flatMap { it.split("|") }
        } else {
            null
        }

        PostData(
            id = rs.getLong("id"),
            title = rs.getString("title"),
            url = rs.getString("url"),
            userId = rs.getLong("user_id"),
            tags = tags,
            createdDate = rs.getTimestamp("created_date").toLocalDateTime(),
            updatedDate = rs.getTimestamp("updated_date").toLocalDateTime(),
        )
    }

    fun getPosts(userId: Long, postType: PostType, offset: Int): List<PostData> {
        val tableName = when (postType) {
            PostType.UNREAD -> "post"
            PostType.ARCHIVE -> "archive_post"
        }

        val sql = """
            SELECT id, title, url, user_id, tags::TEXT[], created_date, updated_date
            FROM $tableName
            WHERE user_id = :user_id
            ORDER BY created_date DESC OFFSET :offset
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("offset", offset)

        return namedParameterJdbcTemplate.query(sql, params, postRowMapper)
    }

    fun findPost(userId: Long, postType: PostType, url: String): PostData? {
        val tableName = when (postType) {
            PostType.UNREAD -> "post"
            PostType.ARCHIVE -> "archive_post"
        }

        val sql = """
            SELECT id, title, url, user_id, tags::TEXT[], created_date, updated_date
            FROM $tableName
            WHERE user_id = :user_id AND url = :url
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("url", url)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, postRowMapper))
    }

    fun getPostSequenceIds(from: Int, to: Int, postType: PostType): List<Long> {
        val seqName = when (postType) {
            PostType.UNREAD -> "post_id_seq"
            PostType.ARCHIVE -> "archive_post_id_seq"
        }

        val sql = """
            SELECT NEXTVAL('$seqName')
            FROM GENERATE_SERIES(:from, :to)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("from", from)
            .addValue("to", to)

        return namedParameterJdbcTemplate.queryForList(sql, params, Long::class.java)
    }

    fun addPost(post: PostStoreData): Long {
        val sql = """
            INSERT INTO post (title, url, user_id)
            VALUES (:title, :url, :user_id)
            RETURNING id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("title", post.title)
            .addValue("url", post.url)
            .addValue("user_id", post.userId)

        return namedParameterJdbcTemplate.queryForObject(sql, params, Long::class.java)!!
    }

    fun addPosts(posts: List<PostStoreDataWithId>, postType: PostType) {
        val tableName = when (postType) {
            PostType.UNREAD -> "post"
            PostType.ARCHIVE -> "archive_post"
        }

        val sql = """
            INSERT INTO $tableName (id, title, url, user_id, tags, created_date, updated_date)
            VALUES (:id, :title, :url, :user_id, :tags::TEXT[], :created_date, :updated_date)
        """.trimIndent()

        val batchArgs = posts.map { post ->
            MapSqlParameterSource()
                .addValue("id", post.id)
                .addValue("title", post.title)
                .addValue("url", post.url)
                .addValue("user_id", post.userId)
                .addValue("tags", post.tags?.toTypedArray())
                .addValue("created_date", post.createdDate)
                .addValue("updated_date", post.updatedDate)
        }.toTypedArray()

        namedParameterJdbcTemplate.batchUpdate(sql, batchArgs)
    }

    fun deletePost(postId: Long, postType: PostType) {
        val tableName = when (postType) {
            PostType.UNREAD -> "post"
            PostType.ARCHIVE -> "archive_post"
        }

        val sql = """
            DELETE
            FROM $tableName
            WHERE id = :post_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)

        namedParameterJdbcTemplate.update(sql, params)
    }

    fun addToArchivePost(postId: Long) {
        val sql = """
            INSERT INTO archive_post(title, url, tags, user_id)
            SELECT title, url, tags, user_id
            FROM post
            WHERE post.id = :post_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)

        namedParameterJdbcTemplate.update(sql, params)
    }

    fun addToUnreadPost(postId: Long) {
        val sql = """
            INSERT INTO post(title, url, tags, user_id)
            SELECT title, url, tags, user_id
            FROM archive_post
            WHERE archive_post.id = :post_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)

        namedParameterJdbcTemplate.update(sql, params)
    }

    fun getRandomPost(userId: Long): PostData? {
        val sql = """
            SELECT id, title, url, user_id, tags, created_date, updated_date
            FROM post
            WHERE user_id = :user_id
            ORDER BY RANDOM()
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, postRowMapper))
    }
}
