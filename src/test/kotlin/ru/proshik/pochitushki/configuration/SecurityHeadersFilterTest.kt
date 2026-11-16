package ru.proshik.pochitushki.configuration

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import ru.proshik.pochitushki.BaseIntegrationTest

@AutoConfigureMockMvc
class SecurityHeadersFilterTest : BaseIntegrationTest() {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private var userId = 0L

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, settings)
               VALUES (7301, 'headers', 'Head', '{"languageCode":"en"}'::jsonb) RETURNING id""",
            Long::class.java,
        )!!
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM users")
    }

    private fun fetch(path: String, cookie: String? = null): MockHttpServletResponse {
        val request = get(path)
        cookie?.let { request.cookie(Cookie("auth_token", it)) }
        return mockMvc.perform(request).andReturn().response
    }

    private fun assertStrict(response: MockHttpServletResponse) {
        val csp = response.getHeader("Content-Security-Policy")!!
        assertTrue(csp.contains("frame-ancestors 'none'"), csp)
        assertTrue(csp.contains("script-src 'self';"), csp)
        assertFalse(csp.contains("telegram.org"), csp)
        assertEquals("DENY", response.getHeader("X-Frame-Options"))
    }

    private fun assertMiniApp(response: MockHttpServletResponse) {
        val csp = response.getHeader("Content-Security-Policy")!!
        assertTrue(csp.contains("script-src 'self' https://telegram.org;"), csp)
        assertTrue(csp.contains("frame-ancestors https://web.telegram.org"), csp)
        assertFalse(csp.contains("frame-ancestors 'none'"), csp)
        assertNull(response.getHeader("X-Frame-Options"))
    }

    @Test
    fun `regular page keeps strict headers`() = assertStrict(fetch("/login"))

    @Test
    fun `tg page allows telegram`() = assertMiniApp(fetch("/tg"))

    @Test
    fun `mini app cookie allows telegram on the feed`() =
        assertMiniApp(fetch("/", jwtService.createToken(userId, miniApp = true)))

    @Test
    fun `regular cookie keeps strict headers on the feed`() =
        assertStrict(fetch("/", jwtService.createToken(userId)))

    @Test
    fun `forged cookie keeps strict headers`() = assertStrict(fetch("/", "eyJhbGciOiJub25lIn0.eyJ0bWEiOnRydWV9."))
}
