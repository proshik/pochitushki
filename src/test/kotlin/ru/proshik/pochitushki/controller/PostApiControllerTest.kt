package ru.proshik.pochitushki.controller

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get as wmGet
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao

@AutoConfigureMockMvc
class PostApiControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var postDao: PostDao

    private var userId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (7002, 'apitest', 'Api', 'Test',
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
        BaseIntegrationTest.wireMockTelegramApi.resetMappings()
    }

    private fun withUser(): RequestPostProcessor = RequestPostProcessor { req ->
        req.setAttribute("userId", userId)
        req
    }

    private fun insertPost(title: String = "Test Post", url: String = "https://example.com"): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, userId
        )!!

    private fun insertArchivePost(title: String = "Archive Post", url: String = "https://archive.com"): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO archive_post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, userId
        )!!

    @Test
    fun `GET fragment returns HTML with post card for unread`() {
        insertPost(title = "My Article", url = "https://myarticle.com")

        mockMvc.perform(
            get("/api/v1/posts/fragment")
                .param("type", "unread")
                .param("offset", "0")
                .with(withUser())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("https://myarticle.com")))
    }

    @Test
    fun `GET fragment returns no scroll trigger when fewer than 20 posts`() {
        insertPost()

        mockMvc.perform(
            get("/api/v1/posts/fragment")
                .param("type", "unread")
                .param("offset", "0")
                .with(withUser())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("hx-trigger"))))
    }

    @Test
    fun `GET fragment returns 400 for unknown type`() {
        mockMvc.perform(
            get("/api/v1/posts/fragment")
                .param("type", "bogus")
                .param("offset", "0")
                .with(withUser())
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST posts adds post and returns card fragment`() {
        // Stub the URL so Jsoup can fetch a title without real network call
        // wireMockTelegramApi is a companion object field — access via class name
        BaseIntegrationTest.wireMockTelegramApi.stubFor(
            wmGet(urlEqualTo("/article")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=UTF-8")
                    .withBody("<html><head><title>My Great Article</title></head><body></body></html>")
            )
        )
        val url = "http://localhost:${BaseIntegrationTest.wireMockTelegramApi.port()}/article"

        mockMvc.perform(
            post("/api/v1/posts")
                .param("url", url)
                .with(withUser())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("My Great Article")))

        assertEquals(1, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `POST posts returns 400 for malformed URL`() {
        mockMvc.perform(
            post("/api/v1/posts")
                .param("url", "not-a-url")
                .with(withUser())
        )
            .andExpect(status().isBadRequest)
    }
}
