package ru.proshik.pochitushki.service

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import ru.proshik.pochitushki.BaseIntegrationTest

class PdfCacheServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var pdfCacheService: PdfCacheService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L
    private var otherUserId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = insertUser(7101, "pdfcache")
        otherUserId = insertUser(7102, "pdfcacheother")
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM pdf_cache")
        jdbcTemplate.execute("DELETE FROM users")
    }

    private fun insertUser(telegramId: Long, username: String): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (?, ?, 'Pdf', 'Cache', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java, telegramId, username
        )!!

    private fun ageEntries(days: Int) {
        jdbcTemplate.update("UPDATE pdf_cache SET created_date = NOW() - make_interval(days => ?)", days)
    }

    @Test
    fun `the second request for the same article does not regenerate it`() {
        val calls = AtomicInteger()
        val generate = { calls.incrementAndGet(); byteArrayOf(1, 2, 3) }

        val first = pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)
        val second = pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)

        assertEquals(1, calls.get())
        assertArrayEquals(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3), second)
    }

    @Test
    fun `each engine is cached separately, since they render differently`() {
        val calls = AtomicInteger()
        val generate = { calls.incrementAndGet(); byteArrayOf(calls.get().toByte()) }

        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)
        pdfCacheService.getOrGenerate(userId, "https://a.com", "playwright", generate)

        assertEquals(2, calls.get())
    }

    @Test
    fun `one reader's render is not served to another`() {
        val calls = AtomicInteger()
        val generate = { calls.incrementAndGet(); byteArrayOf(1) }

        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)
        pdfCacheService.getOrGenerate(otherUserId, "https://a.com", "openhtml", generate)

        assertEquals(2, calls.get())
    }

    @Test
    fun `different urls do not collide`() {
        val calls = AtomicInteger()
        val generate = { calls.incrementAndGet(); byteArrayOf(1) }

        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)
        pdfCacheService.getOrGenerate(userId, "https://b.com", "openhtml", generate)

        assertEquals(2, calls.get())
    }

    @Test
    fun `an entry older than the ttl is regenerated`() {
        val calls = AtomicInteger()
        val generate = { calls.incrementAndGet(); byteArrayOf(calls.get().toByte()) }

        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)
        ageEntries(PdfCacheService.TTL_DAYS.toInt() + 1)

        val fresh = pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)

        assertEquals(2, calls.get())
        assertArrayEquals(byteArrayOf(2), fresh)
    }

    @Test
    fun `an entry just inside the ttl is still served`() {
        val calls = AtomicInteger()
        val generate = { calls.incrementAndGet(); byteArrayOf(1) }

        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)
        ageEntries(PdfCacheService.TTL_DAYS.toInt() - 1)

        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)

        assertEquals(1, calls.get())
    }

    @Test
    fun `regenerating replaces the row rather than adding one`() {
        val calls = AtomicInteger()
        val generate = { calls.incrementAndGet(); byteArrayOf(calls.get().toByte()) }

        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)
        ageEntries(PdfCacheService.TTL_DAYS.toInt() + 1)
        pdfCacheService.getOrGenerate(userId, "https://a.com", "openhtml", generate)

        assertEquals(
            1,
            jdbcTemplate.queryForObject("SELECT count(*) FROM pdf_cache WHERE user_id = ?", Int::class.java, userId)
        )
    }

    @Test
    fun `writing prunes this user's expired entries`() {
        val generate = { byteArrayOf(1) }
        pdfCacheService.getOrGenerate(userId, "https://old.com", "openhtml", generate)
        ageEntries(PdfCacheService.TTL_DAYS.toInt() + 1)

        pdfCacheService.getOrGenerate(userId, "https://new.com", "openhtml", generate)

        val urls = jdbcTemplate.queryForList(
            "SELECT url FROM pdf_cache WHERE user_id = ?", String::class.java, userId
        )
        assertEquals(listOf("https://new.com"), urls)
    }

    @Test
    fun `a failed render is not cached`() {
        assertThrows(IllegalStateException::class.java) {
            pdfCacheService.getOrGenerate(userId, "https://boom.com", "openhtml") {
                throw IllegalStateException("render failed")
            }
        }

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM pdf_cache", Int::class.java))
    }
}
