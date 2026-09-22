package ru.proshik.pochitushki.repository

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import ru.proshik.pochitushki.BaseIntegrationTest

/**
 * Migration 6 converts the legacy Pocket-import `tags[]` arrays into real labels.
 *
 * Liquibase runs it once, against an empty test database, so it would otherwise never
 * touch a single row here. This replays the migration's own SQL over seeded data — the
 * statements are read from the migration file, not copied, so the test fails if the
 * shipped SQL and the intent drift apart.
 */
class LabelBackfillMigrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L
    private var otherUserId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = insertUser(6101, "backfill")
        otherUserId = insertUser(6102, "backfillother")
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM archive_post_label")
        jdbcTemplate.execute("DELETE FROM post_label")
        jdbcTemplate.execute("DELETE FROM label")
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    private fun insertUser(telegramId: Long, username: String): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (?, ?, 'Back', 'Fill', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java, telegramId, username
        )!!

    private fun runMigration() {
        val sql = ClassPathResource("liquibase/scripts/6_backfill_labels_from_tags.sql")
            .inputStream.bufferedReader().readText()
            .lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")

        sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { jdbcTemplate.execute(it) }
    }

    private fun insertTagged(table: String, title: String, url: String, tags: String, owner: Long = userId): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO $table(title, url, user_id, tags) VALUES (?, ?, ?, $tags::TEXT[]) RETURNING id",
            Long::class.java, title, url, owner
        )!!

    private fun labelsOf(table: String, postId: Long): List<String> =
        jdbcTemplate.queryForList(
            "SELECT l.name FROM $table pl JOIN label l ON l.id = pl.label_id WHERE pl.post_id = ? ORDER BY l.name",
            String::class.java, postId
        ).map { it!! }

    @Test
    fun `plain tags become labels linked to their post`() {
        val postId = insertTagged("post", "Tagged", "https://a.com", "ARRAY['kotlin','spring']")

        runMigration()

        assertEquals(listOf("kotlin", "spring"), labelsOf("post_label", postId))
    }

    @Test
    fun `the same tag on two posts becomes one label`() {
        val first = insertTagged("post", "One", "https://a.com", "ARRAY['kotlin']")
        val second = insertTagged("post", "Two", "https://b.com", "ARRAY['kotlin']")

        runMigration()

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM label", Int::class.java))
        assertEquals(listOf("kotlin"), labelsOf("post_label", first))
        assertEquals(listOf("kotlin"), labelsOf("post_label", second))
    }

    @Test
    fun `pipe-joined array elements are split, as the row mapper has always done`() {
        // The pre-3.7 exporter joined tags with '|' inside a single array element.
        val postId = insertTagged("post", "Legacy", "https://a.com", "ARRAY['kotlin|spring|jvm']")

        runMigration()

        assertEquals(listOf("jvm", "kotlin", "spring"), labelsOf("post_label", postId))
    }

    @Test
    fun `names are normalised the way LabelService would normalise them`() {
        val postId = insertTagged("post", "Messy", "https://a.com", "ARRAY['  #kotlin  ','multi   word']")

        runMigration()

        assertEquals(listOf("kotlin", "multi word"), labelsOf("post_label", postId))
    }

    @Test
    fun `tags differing only by decoration collapse onto one label`() {
        val postId = insertTagged("post", "Dup", "https://a.com", "ARRAY['kotlin','#kotlin',' kotlin ']")

        runMigration()

        assertEquals(listOf("kotlin"), labelsOf("post_label", postId))
    }

    @Test
    fun `blank and hash-only tags are dropped`() {
        val postId = insertTagged("post", "Blank", "https://a.com", "ARRAY['','   ','#','ok']")

        runMigration()

        assertEquals(listOf("ok"), labelsOf("post_label", postId))
    }

    @Test
    fun `an over-long tag is cut to the label length limit`() {
        val long = "x".repeat(60)
        val postId = insertTagged("post", "Long", "https://a.com", "ARRAY['$long']")

        runMigration()

        assertEquals(listOf("x".repeat(40)), labelsOf("post_label", postId))
    }

    @Test
    fun `archived posts are backfilled into their own join table`() {
        val postId = insertTagged("archive_post", "Archived", "https://a.com", "ARRAY['security']")

        runMigration()

        assertEquals(listOf("security"), labelsOf("archive_post_label", postId))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM post_label", Int::class.java))
    }

    @Test
    fun `the same tag text under two users stays two labels`() {
        val mine = insertTagged("post", "Mine", "https://a.com", "ARRAY['kotlin']")
        val theirs = insertTagged("post", "Theirs", "https://b.com", "ARRAY['kotlin']", owner = otherUserId)

        runMigration()

        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM label", Int::class.java))
        assertEquals(listOf("kotlin"), labelsOf("post_label", mine))
        assertEquals(listOf("kotlin"), labelsOf("post_label", theirs))
        // and each link points at its owner's label
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """SELECT count(*) FROM post_label pl
                   JOIN post p ON p.id = pl.post_id
                   JOIN label l ON l.id = pl.label_id
                   WHERE l.user_id <> p.user_id""",
                Int::class.java
            )
        )
    }

    @Test
    fun `posts without tags are left alone`() {
        jdbcTemplate.update("INSERT INTO post(title, url, user_id) VALUES ('Bare', 'https://bare.com', ?)", userId)

        runMigration()

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM label", Int::class.java))
    }

    @Test
    fun `running it twice changes nothing`() {
        val postId = insertTagged("post", "Idempotent", "https://a.com", "ARRAY['kotlin','spring']")

        runMigration()
        runMigration()

        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM label", Int::class.java))
        assertEquals(listOf("kotlin", "spring"), labelsOf("post_label", postId))
    }

    @Test
    fun `the tags column is left untouched, so a revert restores the old rendering`() {
        val postId = insertTagged("post", "Kept", "https://a.com", "ARRAY['kotlin']")

        runMigration()

        val tags = jdbcTemplate.queryForObject(
            "SELECT array_to_string(tags, ',') FROM post WHERE id = ?", String::class.java, postId
        )
        assertTrue(tags == "kotlin", "expected the legacy column to survive the backfill, got $tags")
    }
}
