package ru.proshik.pochitushki.repository

import org.springframework.dao.support.DataAccessUtils
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import ru.proshik.pochitushki.model.LabelData
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.model.LabelWithCount

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

    /**
     * Labels with their post counts. The counts are subqueries rather than joins so a
     * label with no posts still comes back — the /labels page has to show it to let the
     * reader delete it.
     */
    fun getLabelsWithCounts(userId: Long): List<LabelWithCount> = labelsWithCounts(userId, limit = null, offset = 0)

    /**
     * The label filter over the feed (`/`, [withArchive] = false) and "all" (`/all`, true): labels
     * carrying at least one post in that section, with that section's count. Busiest first,
     * then by name. Join tables only ever link the owner's posts (see [attachLabel]), so the
     * owner check on `label` covers both.
     */
    fun getShelfLabelCounts(userId: Long, withArchive: Boolean): List<LabelWithCount> {
        val links = if (withArchive) {
            "SELECT label_id FROM post_label UNION ALL SELECT label_id FROM archive_post_label"
        } else {
            "SELECT label_id FROM post_label"
        }
        val sql = """
            SELECT l.id, l.name, count(*) AS post_count
            FROM label l
            JOIN ($links) x ON x.label_id = l.id
            WHERE l.user_id = :user_id
            GROUP BY l.id, l.name
            ORDER BY post_count DESC, l.name
        """.trimIndent()

        return namedParameterJdbcTemplate.query(sql, MapSqlParameterSource().addValue("user_id", userId)) { rs, _ ->
            LabelWithCount(id = rs.getLong("id"), name = rs.getString("name"), postCount = rs.getInt("post_count"))
        }
    }

    /** One page of [getLabelsWithCounts], same order (name; names are unique per user). */
    fun getLabelsWithCountsPage(userId: Long, limit: Int, offset: Int): List<LabelWithCount> =
        labelsWithCounts(userId, limit, offset)

    fun countLabels(userId: Long): Int =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT count(*) FROM label WHERE user_id = :user_id",
            MapSqlParameterSource().addValue("user_id", userId),
            Int::class.java,
        ) ?: 0

    /**
     * Zero-based index of the label in the name order of the /labels pages, or null when the
     * user has no such label. Compared with the same collation the ORDER BY uses.
     */
    fun positionOf(labelId: Long, userId: Long): Int? {
        val sql = """
            SELECT (SELECT count(*) FROM label l WHERE l.user_id = :user_id AND l.name < t.name)
            FROM label t
            WHERE t.id = :label_id AND t.user_id = :user_id
        """.trimIndent()
        val params = MapSqlParameterSource().addValue("user_id", userId).addValue("label_id", labelId)
        return namedParameterJdbcTemplate.query(sql, params) { rs, _ -> rs.getInt(1) }.firstOrNull()
    }

    private fun labelsWithCounts(userId: Long, limit: Int?, offset: Int): List<LabelWithCount> {
        val sql = """
            SELECT l.id, l.name,
                   (SELECT count(*) FROM post_label WHERE label_id = l.id)
                 + (SELECT count(*) FROM archive_post_label WHERE label_id = l.id) AS post_count
            FROM label l
            WHERE l.user_id = :user_id
            ORDER BY l.name
        """.trimIndent() + (if (limit != null) " LIMIT :limit OFFSET :offset" else "")

        val params = MapSqlParameterSource().addValue("user_id", userId)
            .addValue("limit", limit).addValue("offset", offset)
        return namedParameterJdbcTemplate.query(sql, params) { rs, _ ->
            LabelWithCount(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                postCount = rs.getInt("post_count"),
            )
        }
    }

    /** Returns false when the label is not the user's. A name clash surfaces as a DB constraint. */
    fun renameLabel(labelId: Long, userId: Long, name: String): Boolean {
        val sql = """
            UPDATE label
            SET name = :name
            WHERE id = :label_id AND user_id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("label_id", labelId)
            .addValue("user_id", userId)
            .addValue("name", name)

        return namedParameterJdbcTemplate.update(sql, params) > 0
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

            // Chunked because `IN (:post_ids)` expands to one bind parameter per id and the
            // PostgreSQL wire protocol tops out at 65535 of them. A shelf page is 20 ids, but
            // export hands over every post the user owns.
            for (chunk in ids.chunked(ID_CHUNK_SIZE)) {
                namedParameterJdbcTemplate.query(
                    sql,
                    MapSqlParameterSource().addValue("post_ids", chunk)
                ) { rs, _ ->
                    val key = target to rs.getLong("post_id")
                    result.getOrPut(key) { mutableListOf() }
                        .add(LabelData(id = rs.getLong("id"), name = rs.getString("name")))
                }
            }
        }

        return result
    }

    companion object {
        /** Ids per `IN (...)` query — well under the 65535-parameter protocol limit. */
        private const val ID_CHUNK_SIZE = 1000
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
