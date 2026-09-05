package ru.proshik.pochitushki.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import ru.proshik.pochitushki.BaseIntegrationTest

@AutoConfigureMockMvc
class ProfileApiControllerTest : BaseIntegrationTest() {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (9001, 'profiletest', 'Profile', 'Test',
                       '{"languageCode":"ru","tgFeedEntriesNumber":5}'::jsonb)
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
    fun `POST settings updates languageCode`() {
        mockMvc.perform(
            post("/api/v1/profile/settings")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"languageCode":"en"}""")
        ).andExpect(status().isOk)

        val lang = jdbcTemplate.queryForObject(
            "SELECT settings->>'languageCode' FROM users WHERE id = ?",
            String::class.java, userId
        )
        assertEquals("en", lang)
    }

    @Test
    fun `POST settings updates tgFeedEntriesNumber`() {
        mockMvc.perform(
            post("/api/v1/profile/settings")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tgFeedEntriesNumber":20}""")
        ).andExpect(status().isOk)

        val count = jdbcTemplate.queryForObject(
            "SELECT (settings->>'tgFeedEntriesNumber')::int FROM users WHERE id = ?",
            Int::class.java, userId
        )
        assertEquals(20, count)
    }

    @Test
    fun `POST settings turns photo covers off`() {
        mockMvc.perform(
            post("/api/v1/profile/settings")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"showOgCovers":false}""")
        ).andExpect(status().isOk)

        val show = jdbcTemplate.queryForObject(
            "SELECT (settings->>'showOgCovers')::boolean FROM users WHERE id = ?",
            Boolean::class.java, userId
        )
        assertEquals(false, show)
    }

    @Test
    fun `a settings row written before showOgCovers existed reads as on`() {
        // The seed row in setUp has no showOgCovers key at all.
        mockMvc.perform(
            post("/api/v1/profile/settings")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"languageCode":"en"}""")
        ).andExpect(status().isOk)

        val show = jdbcTemplate.queryForObject(
            "SELECT (settings->>'showOgCovers')::boolean FROM users WHERE id = ?",
            Boolean::class.java, userId
        )
        assertEquals(true, show)
    }

    @Test
    fun `patching one setting does not reset the photo cover choice`() {
        mockMvc.perform(
            post("/api/v1/profile/settings")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"showOgCovers":false}""")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/profile/settings")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tgFeedEntriesNumber":3}""")
        ).andExpect(status().isOk)

        val show = jdbcTemplate.queryForObject(
            "SELECT (settings->>'showOgCovers')::boolean FROM users WHERE id = ?",
            Boolean::class.java, userId
        )
        assertEquals(false, show)
    }

    @Test
    fun `POST settings without auth returns 401`() {
        mockMvc.perform(
            post("/api/v1/profile/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"languageCode":"en"}""")
        ).andExpect(status().isUnauthorized)
    }
}
