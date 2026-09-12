package ru.proshik.pochitushki.repository

import java.security.MessageDigest
import org.springframework.dao.support.DataAccessUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PdfCacheDao(private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate) {

    /**
     * The cached render, or null if there is none or it is older than [ttlDays].
     *
     * The age check is in the query rather than in Kotlin so a stale row can never be
     * served by a caller that forgot to look at the timestamp.
     */
    fun find(userId: Long, url: String, engine: String, ttlDays: Long): ByteArray? {
        val sql = """
            SELECT content
            FROM pdf_cache
            WHERE user_id = :user_id AND url_hash = :url_hash AND engine = :engine
              AND created_date > NOW() - make_interval(days => :ttl_days)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("url_hash", hash(url))
            .addValue("engine", engine)
            .addValue("ttl_days", ttlDays.toInt())

        return DataAccessUtils.singleResult(
            namedParameterJdbcTemplate.query(sql, params) { rs, _ -> rs.getBytes("content") }
        )
    }

    fun store(userId: Long, url: String, engine: String, content: ByteArray) {
        val sql = """
            INSERT INTO pdf_cache(user_id, url_hash, engine, url, content, created_date)
            VALUES (:user_id, :url_hash, :engine, :url, :content, NOW())
            ON CONFLICT (user_id, url_hash, engine)
            DO UPDATE SET content = EXCLUDED.content, url = EXCLUDED.url, created_date = NOW()
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("url_hash", hash(url))
            .addValue("engine", engine)
            .addValue("url", url)
            .addValue("content", content)

        namedParameterJdbcTemplate.update(sql, params)
    }

    /**
     * Drops this user's expired rows. Called on write rather than from a scheduler:
     * an entry nobody asks for again would otherwise sit in the table forever, and a
     * cron for one table is more moving parts than the problem deserves.
     */
    fun deleteExpired(userId: Long, ttlDays: Long): Int {
        val sql = """
            DELETE FROM pdf_cache
            WHERE user_id = :user_id AND created_date <= NOW() - make_interval(days => :ttl_days)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("ttl_days", ttlDays.toInt())

        return namedParameterJdbcTemplate.update(sql, params)
    }

    /** Drops every expired row, whoever wrote it — the scheduled counterpart of the call above. */
    fun deleteExpired(ttlDays: Long): Int {
        val sql = """
            DELETE FROM pdf_cache
            WHERE created_date <= NOW() - make_interval(days => :ttl_days)
        """.trimIndent()

        return namedParameterJdbcTemplate.update(sql, MapSqlParameterSource().addValue("ttl_days", ttlDays.toInt()))
    }

    // The url itself is stored alongside for debugging; the hash is what keeps the
    // primary key a fixed size regardless of how long a link is.
    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
