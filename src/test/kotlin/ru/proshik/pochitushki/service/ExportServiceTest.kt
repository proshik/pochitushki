package ru.proshik.pochitushki.service

import java.io.File
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao

class ExportServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var exportService: ExportService

    @Autowired
    private lateinit var importService: ImportService

    @Autowired
    private lateinit var postDao: PostDao

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (888, 'exportuser', 'Export', 'User', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java
        )!!
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    @Test
    fun `export returns null when user has no posts`() {
        val file = exportService.export(userId)
        assertNull(file)
    }

    @Test
    fun `export returns ZIP file when user has posts`() {
        postDao.addPost(PostStoreData("Title", "https://example.com", userId))

        val file = exportService.export(userId)

        assertNotNull(file)
        assertTrue(file!!.name.endsWith(".zip"))
        assertTrue(file.exists())

        file.delete()
    }

    @Test
    fun `export CSV has correct headers`() {
        postDao.addPost(PostStoreData("Title", "https://example.com", userId))

        val file = exportService.export(userId)!!
        val csvContent = readFirstCsvFromZip(file)
        val headerLine = csvContent.trim().lines().first()

        assertEquals("title,url,time_added,tags,status,is_favorite", headerLine)

        file.delete()
    }

    @Test
    fun `export encodes time_added as epoch seconds and joins tags with comma`() {
        // addPost() does not persist tags; insert directly via JDBC to test tag export
        jdbcTemplate.update(
            "INSERT INTO post (title, url, user_id, tags) VALUES ('Article', 'https://test.com', $userId, ARRAY['kotlin','spring'])"
        )

        val file = exportService.export(userId)!!
        val csvContent = readFirstCsvFromZip(file)
        val lines = csvContent.trim().lines()
        assertEquals(2, lines.size)

        // time_added is the 3rd field — must be parseable as positive Long
        val dataFields = parseSimpleCsvLine(lines[1])
        val timeAdded = dataFields[2].toLong()
        assertTrue(timeAdded > 0L)

        // tags joined with comma (CSV will quote the field)
        assertTrue(lines[1].contains("kotlin,spring"))

        // status for unread post
        assertTrue(lines[1].contains("unread"))

        file.delete()
    }

    @Test
    fun `export round-trips through import preserving all fields`() {
        // addPost() does not persist tags; insert directly via JDBC
        val postId = jdbcTemplate.queryForObject(
            "INSERT INTO post (title, url, user_id, tags) VALUES ('Round Trip', 'https://rt.com', $userId, ARRAY['a','b']) RETURNING id",
            Long::class.java
        )!!
        postDao.toggleFavorite(postId, PostType.UNREAD)

        val zipFile = exportService.export(userId)!!

        // Delete original post so we can verify import independently
        postDao.deletePost(postId, PostType.UNREAD)
        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))

        // Re-import
        importService.importZipArchive(userId, zipFile)

        val posts = postDao.getPosts(userId, PostType.UNREAD, 10, 0)
        assertEquals(1, posts.size)
        val post = posts[0]
        assertEquals("Round Trip", post.title)
        assertEquals("https://rt.com", post.url)
        assertEquals(listOf("a", "b"), post.tags)
        assertTrue(post.isFavorite)

        zipFile.delete()
    }

    // --- Helpers ---

    private fun readFirstCsvFromZip(file: File): String {
        ZipInputStream(file.inputStream()).use { zip ->
            zip.nextEntry ?: error("ZIP is empty")
            return String(zip.readAllBytes())
        }
    }

    /** Parse a simple CSV line that may contain one quoted field. */
    private fun parseSimpleCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val current = StringBuilder()
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }
}
