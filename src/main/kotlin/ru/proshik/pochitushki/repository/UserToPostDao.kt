package ru.proshik.pochitushki.repository

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.proshik.pochitushki.model.UserToPostData

//@Repository
class UserToPostDao(private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate) {

    private val userToPostRowMapper = RowMapper { rs, _ ->
        UserToPostData(
            postId = rs.getLong("post_id"),
            createdData = rs.getTimestamp("created_date").toLocalDateTime(),
        )
    }

    fun addUserToPost(userId: Long, postId: Long) {
        val sql = """
            INSERT INTO user_to_post(user_id, post_id)
            VALUES (:user_id, :post_id)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("post_id", postId)

        namedParameterJdbcTemplate.update(sql, params)
    }

    fun addUserToPostBatch(userIdToPostId: List<Pair<Long, Long>>) {
        val sql = """
            INSERT INTO user_to_post(user_id, post_id)
            VALUES (:user_id, :post_id)
        """.trimIndent()

        val params = userIdToPostId.map { (userId, postId) ->
            MapSqlParameterSource()
                .addValue("user_id", userId)
                .addValue("post_id", postId)
        }.toTypedArray()

        namedParameterJdbcTemplate.batchUpdate(sql, params)
    }

    fun getUserToPosts(userId: Long): List<UserToPostData> {
        val sql = """
            SELECT post_id, created_date
            FROM user_to_post
            WHERE user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)

        return namedParameterJdbcTemplate.query(sql, params, userToPostRowMapper)
    }

    fun deleteUserToPost(userId: Long, postId: Long) {
        val sql = """
            DELETE
            FROM user_to_post
            WHERE user_id = :user_id
              AND post_id = :post_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("post_id", postId)

        namedParameterJdbcTemplate.update(sql, params)
    }
}