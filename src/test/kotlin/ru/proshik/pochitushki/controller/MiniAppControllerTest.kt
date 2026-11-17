package ru.proshik.pochitushki.controller

import jakarta.servlet.http.Cookie
import java.time.Instant
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.service.miniapp.InitDataSigner

@AutoConfigureMockMvc
class MiniAppControllerTest : BaseIntegrationTest() {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    // Profile "test" sets telegram.token=some_token.
    private val botToken = "some_token"
    private val telegramId = 777_001L

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM users")
    }

    private fun initData(
        authDate: Instant = Instant.now(),
        token: String = botToken,
        startParam: String? = null,
    ): String {
        val fields = mutableMapOf(
            "auth_date" to "${authDate.epochSecond}",
            "query_id" to "AAH",
            "user" to """{"id":$telegramId,"first_name":"Mini","username":"mini_user","language_code":"en"}""",
        )
        startParam?.let { fields["start_param"] = it }
        return InitDataSigner.sign(fields, token)
    }

    private fun login(initData: String, page: String? = null, origin: String? = null): MvcResult {
        val body = buildString {
            append("""{"initData":"${initData.replace("\"", "\\\"")}"""")
            page?.let { append(""","page":"$it"""") }
            append("}")
        }
        val request = post("/auth/telegram/webapp").contentType(MediaType.APPLICATION_JSON).content(body)
        origin?.let { request.header("Origin", it) }
        return mockMvc.perform(request).andReturn()
    }

    private fun authCookie(result: MvcResult): String? =
        result.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("auth_token=") }

    @Test
    fun `valid init data logs in and sets the cookie`() {
        val result = login(initData())

        assertEquals(200, result.response.status)
        assertTrue(result.response.contentAsString.contains("\"redirect\":\"/\""), result.response.contentAsString)
        val cookie = authCookie(result)
        assertNotNull(cookie)
        assertTrue(cookie!!.contains("HttpOnly") && cookie.contains("SameSite=Lax"), cookie)
        val token = cookie.substringAfter("auth_token=").substringBefore(";")
        assertEquals(true, jwtService.parseToken(token)?.miniApp)
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE telegram_id = ?", Int::class.java, telegramId))
    }

    @Test
    fun `existing bot user is reused`() {
        val existing = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, settings)
               VALUES (?, 'mini_user', 'Mini', '{"languageCode":"ru"}'::jsonb) RETURNING id""",
            Long::class.java, telegramId,
        )!!

        val token = authCookie(login(initData()))!!.substringAfter("auth_token=").substringBefore(";")

        assertEquals(existing, jwtService.parseToken(token)?.userId)
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM users", Int::class.java))
    }

    @Test
    fun `bad signature is refused`() {
        val result = login(initData(token = "another:token"))
        assertEquals(401, result.response.status)
        assertNull(authCookie(result))
    }

    @Test
    fun `stale init data is refused`() {
        val result = login(initData(authDate = Instant.now().minusSeconds(25 * 3600)))
        assertEquals(401, result.response.status)
        assertNull(authCookie(result))
    }

    @Test
    fun `page whitelist`() {
        assertTrue(login(initData(), page = "archive").response.contentAsString.contains("\"redirect\":\"/archive\""))
        assertTrue(login(initData(), page = "//evil.com").response.contentAsString.contains("\"redirect\":\"/\""))
        assertTrue(login(initData(startParam = "favorites")).response.contentAsString.contains("\"redirect\":\"/favorites\""))
    }

    @Test
    fun `cross-origin post is rejected`() {
        assertEquals(403, login(initData(), origin = "https://evil.example").response.status)
    }

    @Test
    fun `session probe`() {
        mockMvc.perform(get("/auth/telegram/webapp/session")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/auth/telegram/webapp/session").cookie(Cookie("auth_token", jwtService.createToken(1L, miniApp = true))))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/auth/telegram/webapp/session").cookie(Cookie("auth_token", "garbage")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `tg page is public`() {
        mockMvc.perform(get("/tg"))
            .andExpect(status().isOk)
            .andExpect(view().name("tg"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(containsString("/js/tg-login.js")))
    }
}
