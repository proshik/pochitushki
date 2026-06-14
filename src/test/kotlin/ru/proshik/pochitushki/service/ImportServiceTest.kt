package ru.proshik.pochitushki.service

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao

class ImportServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var importService: ImportService

    @Autowired
    private lateinit var postDao: PostDao

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L
    private val tempFiles = mutableListOf<File>()

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (777, 'importuser', 'Import', 'User', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java
        )!!
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `importZipArchive saves unread posts from valid Pocket ZIP`() {
        val zip = createZip(
            "export.csv", pocketCsv(
                row("Article One", "https://one.com", 1712000000L, "", "unread"),
                row("Article Two", "https://two.com", 1712001000L, "", "unread"),
            )
        )

        importService.importZipArchive(userId, zip)

        assertEquals(2, postDao.getPostCount(userId, PostType.UNREAD))
        assertEquals(0, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `importZipArchive saves archive posts from valid Pocket ZIP`() {
        val zip = createZip(
            "export.csv", pocketCsv(
                row("Archived Article", "https://arch.com", 1712000000L, "", "read"),
            )
        )

        importService.importZipArchive(userId, zip)

        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))
        assertEquals(1, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `importZipArchive partitions posts by status correctly`() {
        val zip = createZip(
            "export.csv", pocketCsv(
                row("Unread One", "https://u1.com", 1712000000L, "", "unread"),
                row("Unread Two", "https://u2.com", 1712001000L, "", "unread"),
                row("Archived", "https://a1.com", 1712002000L, "", "read"),
            )
        )

        importService.importZipArchive(userId, zip)

        assertEquals(2, postDao.getPostCount(userId, PostType.UNREAD))
        assertEquals(1, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `importZipArchive does nothing for empty ZIP`() {
        val zip = createEmptyZip()

        importService.importZipArchive(userId, zip)

        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))
        assertEquals(0, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `importZipArchive saves tags from comma-separated field`() {
        val zip = createZip(
            "export.csv", pocketCsv(
                row("Tagged Article", "https://tagged.com", 1712000000L, "kotlin,spring,jvm", "unread"),
            )
        )

        importService.importZipArchive(userId, zip)

        val posts = postDao.getPosts(userId, PostType.UNREAD, 10, 0)
        assertEquals(1, posts.size)
        val tags = posts[0].tags
        assertNotNull(tags)
        assertEquals(listOf("kotlin", "spring", "jvm"), tags)
    }

    @Test
    fun `importZipArchive preserves is_favorite flag`() {
        val zip = createZip(
            "export.csv", pocketCsv(
                row("Fav Post", "https://fav.com", 1712000000L, "", "unread", true),
                row("Normal Post", "https://normal.com", 1712001000L, "", "unread", false),
            )
        )

        importService.importZipArchive(userId, zip)

        val posts = postDao.getPosts(userId, PostType.UNREAD, 10, 0)
        assertEquals(2, posts.size)
        val fav = posts.first { it.url == "https://fav.com" }
        val normal = posts.first { it.url == "https://normal.com" }
        assertTrue(fav.isFavorite)
        assertFalse(normal.isFavorite)
    }

    @Test
    fun `importZipArchive preserves is_favorite flag on archive posts`() {
        val zip = createZip(
            "export.csv", pocketCsv(
                row("Fav Archive Post", "https://favarch.com", 1712000000L, "", "read", true),
            )
        )

        importService.importZipArchive(userId, zip)

        val posts = postDao.getPosts(userId, PostType.ARCHIVE, 10, 0)
        assertEquals(1, posts.size)
        assertTrue(posts[0].isFavorite)
    }

    @Test
    fun `importZipArchive rejects archive with too many CSV entries`() {
        val tmpFile = File.createTempFile("test_import_bomb_", ".zip").also { tempFiles.add(it) }
        ZipOutputStream(tmpFile.outputStream()).use { zip ->
            repeat(ImportService.MAX_CSV_ENTRIES + 1) { i ->
                zip.putNextEntry(ZipEntry("posts_$i.csv"))
                zip.write(pocketCsv(row("T$i", "https://e$i.com", 1712000000L, "", "unread")).toByteArray())
                zip.closeEntry()
            }
        }

        org.junit.jupiter.api.Assertions.assertThrows(ImportLimitException::class.java) {
            importService.importZipArchive(userId, tmpFile)
        }
    }

    @Test
    fun `importZipArchive skips blank-url rows and tolerates blank time_added`() {
        val csv = """
            title,url,time_added,tags,status,is_favorite
            Good Post,https://good.com,,,unread,false
            No Url Post,,1712000000,,unread,false
        """.trimIndent()
        val zip = createZip("export.csv", csv)

        importService.importZipArchive(userId, zip)

        // blank-url row skipped; blank time_added row still imported (createdDate defaulted)
        assertEquals(1, postDao.getPostCount(userId, PostType.UNREAD))
    }

    // --- Helpers ---

    private fun pocketCsv(vararg rows: String): String {
        val header = "title,url,time_added,tags,status,is_favorite"
        return (listOf(header) + rows.toList()).joinToString("\n")
    }

    private fun row(title: String, url: String, timeAdded: Long, tags: String, status: String, isFavorite: Boolean = false): String {
        val quotedTags = if (tags.contains(",")) "\"$tags\"" else tags
        return "$title,$url,$timeAdded,$quotedTags,$status,$isFavorite"
    }

    private fun createZip(fileName: String, csvContent: String): File {
        val tmpFile = File.createTempFile("test_import_", ".zip").also { tempFiles.add(it) }
        ZipOutputStream(tmpFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(fileName))
            zip.write(csvContent.toByteArray())
            zip.closeEntry()
        }
        return tmpFile
    }

    private fun createEmptyZip(): File {
        val tmpFile = File.createTempFile("test_import_empty_", ".zip").also { tempFiles.add(it) }
        ZipOutputStream(tmpFile.outputStream()).use { /* no entries */ }
        return tmpFile
    }
}
