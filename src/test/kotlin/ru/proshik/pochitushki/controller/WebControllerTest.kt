package ru.proshik.pochitushki.controller

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import ru.proshik.pochitushki.BaseIntegrationTest

@AutoConfigureMockMvc
class WebControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (7001, 'webtest', 'Web', 'Test',
                       '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
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

    private fun withUser(): RequestPostProcessor = RequestPostProcessor { req ->
        req.setAttribute("userId", userId)
        req
    }

    @Test
    fun `GET feed returns 200 with post-list element`() {
        mockMvc.perform(get("/feed").with(withUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"post-list\"")))
    }

    @Test
    fun `GET archive returns 200 with post-list element`() {
        mockMvc.perform(get("/archive").with(withUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"post-list\"")))
    }

    @Test
    fun `GET favorites returns 200 with post-list element`() {
        mockMvc.perform(get("/favorites").with(withUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"post-list\"")))
    }

    @Test
    fun `GET profile returns 200 with username`() {
        mockMvc.perform(get("/profile").with(withUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("webtest")))
    }
}
