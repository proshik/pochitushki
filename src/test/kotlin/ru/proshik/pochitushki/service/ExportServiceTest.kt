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
    private lateinit var labelService: LabelService

    private fun attachLabels(postId: Long, vararg names: String) {
        names.forEach { name ->
            labelService.attachLabel(
                postId, labelService.createLabel(userId, name).id, userId,
                ru.proshik.pochitushki.model.LabelTarget.UNREAD
            )
        }
    }

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val tempFiles = mutableListOf<File>()

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
        tempFiles.forEach { it.delete() }
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

        val file = exportService.export(userId)?.also { tempFiles.add(it) }

        assertNotNull(file)
        assertTrue(file!!.name.endsWith(".zip"))
        assertTrue(file.exists())
    }

    @Test
    fun `export CSV has correct headers`() {
        postDao.addPost(PostStoreData("Title", "https://example.com", userId))

        val file = exportService.export(userId)!!.also { tempFiles.add(it) }
        val csvContent = readFirstCsvFromZip(file)
        val headerLine = csvContent.trim().lines().first()

        assertEquals("title,url,time_added,tags,status,is_favorite", headerLine)
    }

    @Test
    fun `export encodes time_added as epoch seconds and joins tags with comma`() {
        val postId = postDao.addPost(PostStoreData("Article", "https://test.com", userId))
        attachLabels(postId, "kotlin", "spring")

        val file = exportService.export(userId)!!.also { tempFiles.add(it) }
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
    }

    @Test
    fun `export round-trips through import preserving all fields`() {
        val postId = postDao.addPost(PostStoreData("Round Trip", "https://rt.com", userId))
        attachLabels(postId, "a", "b")
        postDao.toggleFavorite(postId, userId, PostType.UNREAD)

        val zipFile = exportService.export(userId)!!.also { tempFiles.add(it) }

        // Delete original post so we can verify import independently
        postDao.deletePost(postId, userId, PostType.UNREAD)
        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))

        // Re-import
        importService.importZipArchive(userId, zipFile)

        val posts = postDao.getPosts(userId, PostType.UNREAD, 10, 0)
        assertEquals(1, posts.size)
        val post = posts[0]
        assertEquals("Round Trip", post.title)
        assertEquals("https://rt.com", post.url)
        assertEquals(listOf("a", "b"), labelService.withLabels(post).labels.map { it.name })
        assertTrue(post.isFavorite)
    }

    @Test
    fun `export neutralizes CSV formula injection in title`() {
        jdbcTemplate.update(
            "INSERT INTO post (title, url, user_id) VALUES ('=cmd|''/c calc''!A1', 'https://evil.com', $userId)"
        )

        val file = exportService.export(userId)!!.also { tempFiles.add(it) }
        val csvContent = readFirstCsvFromZip(file)
        val dataLine = csvContent.trim().lines()[1]

        // the dangerous leading '=' must be neutralized with a leading apostrophe
        assertTrue(dataLine.contains("'=cmd"), "formula trigger should be prefixed: $dataLine")
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
