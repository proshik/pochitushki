package ru.proshik.pochitushki.repository

import org.springframework.dao.support.DataAccessUtils
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import ru.proshik.pochitushki.model.LabelData
import ru.proshik.pochitushki.model.LabelTarget

@Repository
class LabelDao(private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate) {

    private val labelRowMapper = RowMapper { rs, _ ->
        LabelData(id = rs.getLong("id"), name = rs.getString("name"))
    }

    fun getLabels(userId: Long): List<LabelData> {
        val sql = """
            SELECT id, name
            FROM label
            WHERE user_id = :user_id
            ORDER BY name
        """.trimIndent()

        return namedParameterJdbcTemplate.query(
            sql,
            MapSqlParameterSource().addValue("user_id", userId),
            labelRowMapper
        )
    }

    fun findLabel(labelId: Long, userId: Long): LabelData? {
        val sql = """
            SELECT id, name
            FROM label
            WHERE id = :label_id AND user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("label_id", labelId)
            .addValue("user_id", userId)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, labelRowMapper))
    }

    /**
     * Creates the label, or returns the one already carrying that name — creating a
     * label twice is the same intent expressed twice, not an error the caller must handle.
     * The no-op DO UPDATE is what makes RETURNING fire on the conflicting row.
     */
    fun createLabel(userId: Long, name: String): LabelData {
        val sql = """
            INSERT INTO label(user_id, name)
            VALUES (:user_id, :name)
            ON CONFLICT (user_id, name) DO UPDATE SET name = EXCLUDED.name
            RETURNING id, name
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("name", name)

        return DataAccessUtils.requiredSingleResult(
            namedParameterJdbcTemplate.query(sql, params, labelRowMapper)
        )
    }

    fun deleteLabel(labelId: Long, userId: Long): Int {
        val sql = """
            DELETE FROM label
            WHERE id = :label_id AND user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("label_id", labelId)
            .addValue("user_id", userId)

        return namedParameterJdbcTemplate.update(sql, params)
    }

    /**
     * Links post to label. Ownership of both is asserted inside the statement, so a
     * caller that got the ids wrong writes nothing rather than linking across users.
     * Returns false when the link already existed or either id is not the user's.
     */
    fun attachLabel(postId: Long, labelId: Long, userId: Long, target: LabelTarget): Boolean {
        val postTable = if (target == LabelTarget.ARCHIVE) "archive_post" else "post"

        val sql = """
            INSERT INTO ${target.table}(post_id, label_id)
            SELECT p.id, l.id
            FROM $postTable p, label l
            WHERE p.id = :post_id AND p.user_id = :user_id
              AND l.id = :label_id AND l.user_id = :user_id
            ON CONFLICT DO NOTHING
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)
            .addValue("label_id", labelId)
            .addValue("user_id", userId)

        return namedParameterJdbcTemplate.update(sql, params) > 0
    }

    fun detachLabel(postId: Long, labelId: Long, userId: Long, target: LabelTarget): Boolean {
        val postTable = if (target == LabelTarget.ARCHIVE) "archive_post" else "post"

        val sql = """
            DELETE FROM ${target.table} pl
            USING $postTable p
            WHERE pl.post_id = p.id
              AND pl.post_id = :post_id AND pl.label_id = :label_id
              AND p.user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("post_id", postId)
            .addValue("label_id", labelId)
            .addValue("user_id", userId)

        return namedParameterJdbcTemplate.update(sql, params) > 0
    }

    /**
     * Labels for a batch of posts, in one query per table — the alternative is a
     * query per card, which is what a shelf of twenty would cost.
     *
     * Keyed by id alone would be wrong: post.id and archive_post.id are separate
     * sequences and collide routinely, so the key carries the table too.
     */
    fun findLabelsForPosts(unreadIds: List<Long>, archiveIds: List<Long>): Map<Pair<LabelTarget, Long>, List<LabelData>> {
        val result = mutableMapOf<Pair<LabelTarget, Long>, MutableList<LabelData>>()

        for (target in LabelTarget.entries) {
            val ids = if (target == LabelTarget.ARCHIVE) archiveIds else unreadIds
            if (ids.isEmpty()) continue

            val sql = """
                SELECT pl.post_id, l.id, l.name
                FROM ${target.table} pl
                JOIN label l ON l.id = pl.label_id
                WHERE pl.post_id IN (:post_ids)
                ORDER BY l.name
            """.trimIndent()

            namedParameterJdbcTemplate.query(
                sql,
                MapSqlParameterSource().addValue("post_ids", ids)
            ) { rs, _ ->
                val key = target to rs.getLong("post_id")
                result.getOrPut(key) { mutableListOf() }
                    .add(LabelData(id = rs.getLong("id"), name = rs.getString("name")))
            }
        }

        return result
    }

    /** Copies the label links of a post onto the row it was just moved to. */
    fun copyLabels(fromPostId: Long, toPostId: Long, from: LabelTarget, to: LabelTarget): Int {
        val sql = """
            INSERT INTO ${to.table}(post_id, label_id)
            SELECT :to_post_id, label_id
            FROM ${from.table}
            WHERE post_id = :from_post_id
            ON CONFLICT DO NOTHING
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("from_post_id", fromPostId)
            .addValue("to_post_id", toPostId)

        return namedParameterJdbcTemplate.update(sql, params)
    }
}
