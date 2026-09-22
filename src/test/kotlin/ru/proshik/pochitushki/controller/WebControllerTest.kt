package ru.proshik.pochitushki.controller

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.service.LabelService

@AutoConfigureMockMvc
class WebControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var labelService: LabelService

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
        jdbcTemplate.execute("DELETE FROM archive_post_label")
        jdbcTemplate.execute("DELETE FROM post_label")
        jdbcTemplate.execute("DELETE FROM label")
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    @Test
    fun `GET feed returns 200 with post-list element`() {
        mockMvc.perform(get("/").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"post-list\"")))
    }

    @Test
    fun `GET archive returns 200 with post-list element`() {
        mockMvc.perform(get("/archive").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"post-list\"")))
    }

    @Test
    fun `GET favorites returns 200 with post-list element`() {
        mockMvc.perform(get("/favorites").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"post-list\"")))
    }

    @Test
    fun `GET profile returns 200 with username`() {
        mockMvc.perform(get("/profile").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("webtest")))
    }

    @Test
    fun `GET random returns 200 with post-list element`() {
        mockMvc.perform(get("/random").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"post-list\"")))
    }

    @Test
    fun `GET random offers the reshuffle control`() {
        mockMvc.perform(get("/random").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("/api/v1/posts/random-fragment")))
    }

    @Test
    fun `GET random without auth cookie redirects to login`() {
        mockMvc.perform(get("/random"))
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login"))
    }

    @Test
    fun `GET labels lists the user's labels with post counts`() {
        val label = labelService.createLabel(userId, "kotlin")
        val postId = jdbcTemplate.queryForObject(
            "INSERT INTO post(title, url, user_id) VALUES ('P', 'https://p.com', ?) RETURNING id",
            Long::class.java, userId
        )!!
        labelService.attachLabel(postId, label.id, userId, LabelTarget.UNREAD)

        mockMvc.perform(get("/labels").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("kotlin")))
            .andExpect(content().string(containsString("/labels/${label.id}")))
    }

    @Test
    fun `GET labels id shows the posts carrying that label`() {
        val label = labelService.createLabel(userId, "kotlin")
        val postId = jdbcTemplate.queryForObject(
            "INSERT INTO post(title, url, user_id) VALUES ('P', 'https://labelled-page.com', ?) RETURNING id",
            Long::class.java, userId
        )!!
        labelService.attachLabel(postId, label.id, userId, LabelTarget.UNREAD)

        mockMvc.perform(get("/labels/${label.id}").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("https://labelled-page.com")))
    }

    @Test
    fun `GET labels id returns 404 for a label that is not yours`() {
        val otherUserId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (7009, 'someoneelse', 'Some', 'One',
                       '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java
        )!!
        val theirs = labelService.createLabel(otherUserId, "theirs")

        mockMvc.perform(get("/labels/${theirs.id}").with(withAuth(userId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET labels without auth cookie redirects to login`() {
        mockMvc.perform(get("/labels"))
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login"))
        mockMvc.perform(get("/labels/1"))
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login"))
    }

    @Test
    fun `GET feed without auth cookie redirects to login`() {
        mockMvc.perform(get("/"))
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login"))
    }
}
