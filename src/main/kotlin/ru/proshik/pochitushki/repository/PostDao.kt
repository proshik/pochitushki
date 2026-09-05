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
            isFavorite = rs.getBoolean("is_favorite"),
            isArchived = rs.getBoolean("is_archived"),
            ogImageUrl = rs.getString("og_image_url"),
            createdDate = rs.getTimestamp("created_date").toLocalDateTime(),
            updatedDate = rs.getTimestamp("updated_date").toLocalDateTime(),
        )
    }

    fun getPost(postId: Long, userId: Long, postType: PostType): PostData? {
        val (tableName, isArchived) = when (postType) {
            PostType.UNREAD, PostType.FAVORITES -> Pair("post", false)
            PostType.ARCHIVE -> Pair("archive_post", true)
            PostType.ALL -> error("getPost does not support ALL")
        }

        val sql = """
            SELECT id, title, url, user_id, tags::TEXT[], is_favorite, $isArchived as is_archived, og_image_url, created_date, updated_date
            FROM $tableName
            WHERE id = :post_id AND user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, postRowMapper))
    }

    fun getPosts(userId: Long, postType: PostType, limit: Int, offset: Int): List<PostData> {
        if (postType == PostType.ALL) {
            val sql = """
                SELECT id, title, url, user_id, tags::TEXT[], is_favorite, false as is_archived, og_image_url, created_date, updated_date
                FROM post
                WHERE user_id = :user_id
                UNION ALL
                SELECT id, title, url, user_id, tags::TEXT[], is_favorite, true as is_archived, og_image_url, created_date, updated_date
                FROM archive_post
                WHERE user_id = :user_id
                ORDER BY created_date DESC OFFSET :offset LIMIT :limit
            """.trimIndent()

            val params = MapSqlParameterSource()
                .addValue("user_id", userId)
                .addValue("offset", offset)
                .addValue("limit", limit)

            return namedParameterJdbcTemplate.query(sql, params, postRowMapper)
        }

        if (postType == PostType.FAVORITES) {
            val sql = """
                SELECT id, title, url, user_id, tags::TEXT[], is_favorite, false as is_archived, og_image_url, created_date, updated_date
                FROM post
                WHERE user_id = :user_id AND is_favorite = true
                UNION ALL
                SELECT id, title, url, user_id, tags::TEXT[], is_favorite, true as is_archived, og_image_url, created_date, updated_date
                FROM archive_post
                WHERE user_id = :user_id AND is_favorite = true
                ORDER BY created_date DESC OFFSET :offset LIMIT :limit
            """.trimIndent()

            val params = MapSqlParameterSource()
                .addValue("user_id", userId)
                .addValue("offset", offset)
                .addValue("limit", limit)

            return namedParameterJdbcTemplate.query(sql, params, postRowMapper)
        }

        val (tableName, isArchived) = when (postType) {
            PostType.UNREAD -> Pair("post", false)
            PostType.ARCHIVE -> Pair("archive_post", true)
            PostType.FAVORITES, PostType.ALL -> error("unreachable")
        }

        val sql = """
            SELECT id, title, url, user_id, tags::TEXT[], is_favorite, $isArchived as is_archived, og_image_url, created_date, updated_date
            FROM $tableName
            WHERE user_id = :user_id
            ORDER BY created_date DESC OFFSET :offset LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("offset", offset)
            .addValue("limit", limit)

        return namedParameterJdbcTemplate.query(sql, params, postRowMapper)
    }

    /**
     * Every post carrying the label, unread and archived alike — a label is a
     * property of the link, not of which shelf it currently sits on.
     */
    fun getPostsByLabel(userId: Long, labelId: Long, limit: Int, offset: Int): List<PostData> {
        val sql = """
            SELECT p.id, p.title, p.url, p.user_id, p.tags::TEXT[], p.is_favorite, false as is_archived, p.og_image_url, p.created_date, p.updated_date
            FROM post p
            JOIN post_label pl ON pl.post_id = p.id
            WHERE p.user_id = :user_id AND pl.label_id = :label_id
            UNION ALL
            SELECT p.id, p.title, p.url, p.user_id, p.tags::TEXT[], p.is_favorite, true as is_archived, p.og_image_url, p.created_date, p.updated_date
            FROM archive_post p
            JOIN archive_post_label pl ON pl.post_id = p.id
            WHERE p.user_id = :user_id AND pl.label_id = :label_id
            ORDER BY created_date DESC OFFSET :offset LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("label_id", labelId)
            .addValue("offset", offset)
            .addValue("limit", limit)

        return namedParameterJdbcTemplate.query(sql, params, postRowMapper)
    }

    fun getPostCountByLabel(userId: Long, labelId: Long): Int {
        val sql = """
            SELECT count(*) FROM (
                SELECT p.id FROM post p
                JOIN post_label pl ON pl.post_id = p.id
                WHERE p.user_id = :user_id AND pl.label_id = :label_id
                UNION ALL
                SELECT p.id FROM archive_post p
                JOIN archive_post_label pl ON pl.post_id = p.id
                WHERE p.user_id = :user_id AND pl.label_id = :label_id
            ) sub
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("label_id", labelId)

        return namedParameterJdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }

    fun findPost(userId: Long, postType: PostType, url: String): List<PostData> {
        val (tableName, isArchived) = when (postType) {
            PostType.UNREAD, PostType.FAVORITES -> Pair("post", false)
            PostType.ARCHIVE -> Pair("archive_post", true)
            PostType.ALL -> error("findPost does not support ALL")
        }

        val sql = """
            SELECT id, title, url, user_id, tags::TEXT[], is_favorite, $isArchived as is_archived, og_image_url, created_date, updated_date
            FROM $tableName
            WHERE user_id = :user_id AND url ilike :url
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("url", "$url%")

        return namedParameterJdbcTemplate.query(sql, params, postRowMapper)
    }

    fun getPostSequenceIds(from: Int, to: Int, postType: PostType): List<Long> {
        val seqName = when (postType) {
            PostType.UNREAD, PostType.FAVORITES -> "post_id_seq"
            PostType.ARCHIVE -> "archive_post_id_seq"
            PostType.ALL -> error("getPostSequenceIds does not support ALL")
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
            INSERT INTO post (title, url, user_id, og_image_url)
            VALUES (:title, :url, :user_id, :og_image_url)
            RETURNING id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("title", post.title)
            .addValue("url", post.url)
            .addValue("user_id", post.userId)
            .addValue("og_image_url", post.ogImageUrl)

        return namedParameterJdbcTemplate.queryForObject(sql, params, Long::class.java)
            ?: error("addPost: INSERT did not return an id")
    }

    fun addPosts(posts: List<PostStoreDataWithId>, postType: PostType) {
        val tableName = when (postType) {
            PostType.UNREAD, PostType.FAVORITES -> "post"
            PostType.ARCHIVE -> "archive_post"
            PostType.ALL -> error("addPosts does not support ALL")
        }

        val sql = """
            INSERT INTO $tableName (id, title, url, user_id, tags, is_favorite, created_date, updated_date)
            VALUES (:id, :title, :url, :user_id, :tags::TEXT[], :is_favorite, :created_date, :updated_date)
        """.trimIndent()

        val batchArgs = posts.map { post ->
            MapSqlParameterSource()
                .addValue("id", post.id)
                .addValue("title", post.title)
                .addValue("url", post.url)
                .addValue("user_id", post.userId)
                .addValue("tags", post.tags?.toTypedArray())
                .addValue("is_favorite", post.isFavorite)
                .addValue("created_date", post.createdDate)
                .addValue("updated_date", post.updatedDate)
        }.toTypedArray()

        namedParameterJdbcTemplate.batchUpdate(sql, batchArgs)
    }

    fun deletePost(postId: Long, userId: Long, postType: PostType): Int {
        val tableName = when (postType) {
            PostType.UNREAD, PostType.FAVORITES -> "post"
            PostType.ARCHIVE -> "archive_post"
            PostType.ALL -> error("deletePost does not support ALL")
        }

        val sql = """
            DELETE
            FROM $tableName
            WHERE id = :post_id AND user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)
            .addValue("user_id", userId)

        return namedParameterJdbcTemplate.update(sql, params)
    }

    // Returns the new archive-post id, or null if no matching unread post is owned by the user.
    fun addToArchivePost(postId: Long, userId: Long): Long? {
        val sql = """
            INSERT INTO archive_post(title, url, tags, user_id, is_favorite, og_image_url, created_date)
            SELECT title, url, tags, user_id, is_favorite, og_image_url, created_date
            FROM post
            WHERE post.id = :post_id AND post.user_id = :user_id
            RETURNING id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(
            namedParameterJdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("id") }
        )
    }

    // Returns the new unread-post id, or null if no matching archive post is owned by the user.
    fun addToUnreadPost(postId: Long, userId: Long): Long? {
        val sql = """
            INSERT INTO post(title, url, tags, user_id, is_favorite, og_image_url, created_date)
            SELECT title, url, tags, user_id, is_favorite, og_image_url, created_date
            FROM archive_post
            WHERE archive_post.id = :post_id AND archive_post.user_id = :user_id
            RETURNING id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(
            namedParameterJdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("id") }
        )
    }

    // Returns the new is_favorite value, or null if no matching post is owned by the user.
    fun toggleFavorite(postId: Long, userId: Long, postType: PostType): Boolean? {
        val tableName = when (postType) {
            PostType.UNREAD, PostType.FAVORITES -> "post"
            PostType.ARCHIVE -> "archive_post"
            PostType.ALL -> error("toggleFavorite does not support ALL")
        }

        val sql = """
            UPDATE $tableName
            SET is_favorite = NOT is_favorite
            WHERE id = :post_id AND user_id = :user_id
            RETURNING is_favorite
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(
            namedParameterJdbcTemplate.query(sql, params) { rs, _ -> rs.getBoolean("is_favorite") }
        )
    }

    fun getRandomPost(userId: Long): PostData? {
        val sql = """
            SELECT id, title, url, user_id, tags, is_favorite, false as is_archived, og_image_url, created_date, updated_date
            FROM post
            WHERE user_id = :user_id
            ORDER BY RANDOM()
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, postRowMapper))
    }

    // A shuffled handful of unread posts — the /random shelf. ORDER BY RANDOM()
    // sorts the whole user partition, which is fine for a personal reading list;
    // revisit only if a single user ever holds six figures of unread links.
    fun getRandomPosts(userId: Long, count: Int): List<PostData> {
        val sql = """
            SELECT id, title, url, user_id, tags::TEXT[], is_favorite, false as is_archived, og_image_url, created_date, updated_date
            FROM post
            WHERE user_id = :user_id
            ORDER BY RANDOM()
            LIMIT :count
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("count", count)

        return namedParameterJdbcTemplate.query(sql, params, postRowMapper)
    }

    // The oldest unread post — the web feed's "next to read" hero.
    fun getOldestPost(userId: Long): PostData? {
        val sql = """
            SELECT id, title, url, user_id, tags::TEXT[], is_favorite, false as is_archived, og_image_url, created_date, updated_date
            FROM post
            WHERE user_id = :user_id
            ORDER BY created_date ASC
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, postRowMapper))
    }

    fun getPostCount(userId: Long, postType: PostType): Int {
        if (postType == PostType.ALL) {
            val sql = """
                SELECT count(*) FROM (
                    SELECT id FROM post WHERE user_id = :user_id
                    UNION ALL
                    SELECT id FROM archive_post WHERE user_id = :user_id
                ) sub
            """.trimIndent()

            val params = MapSqlParameterSource()
                .addValue("user_id", userId)

            return namedParameterJdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
        }

        if (postType == PostType.FAVORITES) {
            val sql = """
                SELECT count(*) FROM (
                    SELECT id FROM post WHERE user_id = :user_id AND is_favorite = true
                    UNION ALL
                    SELECT id FROM archive_post WHERE user_id = :user_id AND is_favorite = true
                ) sub
            """.trimIndent()

            val params = MapSqlParameterSource()
                .addValue("user_id", userId)

            return namedParameterJdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
        }

        val tableName = when (postType) {
            PostType.UNREAD -> "post"
            PostType.ARCHIVE -> "archive_post"
            PostType.FAVORITES, PostType.ALL -> error("unreachable")
        }

        val sql = """
            SELECT count(*)
            FROM $tableName
            WHERE user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)

        return namedParameterJdbcTemplate.queryForObject(sql, params, Int::class.java) ?: 0
    }
}
