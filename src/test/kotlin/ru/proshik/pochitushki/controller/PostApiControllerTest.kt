package ru.proshik.pochitushki.controller

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get as wmGet
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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
    private var otherUserId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (7002, 'apitest', 'Api', 'Test',
                       '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java
        )!!
        otherUserId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (7003, 'attacker', 'Mal', 'Lory',
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

    private fun insertPost(title: String = "Test Post", url: String = "https://example.com"): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, userId
        )!!

    private fun insertPostWithOgImage(ogImageUrl: String, owner: Long = userId): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO post(title, url, user_id, og_image_url) VALUES (?, ?, ?, ?) RETURNING id",
            Long::class.java, "With Cover", "https://example.com/cover-post", owner, ogImageUrl
        )!!

    /** A one-pixel PNG is enough: the proxy never decodes, it only gates and forwards. */
    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
    )

    private fun stubImage(path: String, contentType: String = "image/png", body: ByteArray = pngBytes) {
        BaseIntegrationTest.wireMockTelegramApi.stubFor(
            wmGet(urlEqualTo(path)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(body)
            )
        )
    }

    private fun imageUrl(path: String) = "http://localhost:${BaseIntegrationTest.wireMockTelegramApi.port()}$path"

    private fun insertArchivePost(title: String = "Archive Post", url: String = "https://archive.com"): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO archive_post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, userId
        )!!

    private fun insertPostFor(ownerId: Long, title: String = "Owned", url: String = "https://owned.com"): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, ownerId
        )!!

    private fun insertArchivePostFor(ownerId: Long, title: String = "Owned", url: String = "https://owned.com"): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO archive_post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, ownerId
        )!!

    @Test
    fun `GET fragment returns HTML with post card for unread`() {
        insertPost(title = "My Article", url = "https://myarticle.com")

        mockMvc.perform(
            get("/api/v1/posts/fragment")
                .param("type", "unread")
                .param("offset", "0")
                .with(withAuth(userId))
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
                .with(withAuth(userId))
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
                .with(withAuth(userId))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET random-fragment returns cards for the user's unread posts`() {
        insertPost(title = "Random Article", url = "https://randomarticle.com")

        mockMvc.perform(get("/api/v1/posts/random-fragment").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("https://randomarticle.com")))
    }

    @Test
    fun `GET random-fragment deals out at most eight cards`() {
        repeat(12) { i -> insertPost(title = "Post $i", url = "https://rnd$i.com") }

        val body = mockMvc.perform(get("/api/v1/posts/random-fragment").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertEquals(8, Regex("class=\"post-card\"").findAll(body).count())
    }

    @Test
    fun `GET random-fragment never carries a scroll sentinel`() {
        repeat(12) { i -> insertPost(title = "Post $i", url = "https://snt$i.com") }

        mockMvc.perform(get("/api/v1/posts/random-fragment").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("hx-trigger"))))
    }

    @Test
    fun `GET random-fragment does not leak another user's posts`() {
        jdbcTemplate.update(
            "INSERT INTO post(title, url, user_id) VALUES (?, ?, ?)",
            "Not Yours", "https://notyours.com", otherUserId
        )

        mockMvc.perform(get("/api/v1/posts/random-fragment").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("https://notyours.com"))))
    }

    @Test
    fun `GET random-fragment without auth returns 401`() {
        mockMvc.perform(get("/api/v1/posts/random-fragment"))
            .andExpect(status().isUnauthorized)
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
                .with(withAuth(userId))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("My Great Article")))

        assertEquals(1, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `POST posts rejects internal address URL (SSRF) and stores nothing`() {
        mockMvc.perform(
            post("/api/v1/posts")
                .param("url", "http://169.254.169.254/latest/meta-data/")
                .with(withAuth(userId))
        ).andExpect(status().isBadRequest)

        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `POST posts returns 400 for malformed URL`() {
        mockMvc.perform(
            post("/api/v1/posts")
                .param("url", "not-a-url")
                .with(withAuth(userId))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST archive moves unread post to archive`() {
        val postId = insertPost()

        mockMvc.perform(
            post("/api/v1/posts/$postId/archive").with(withAuth(userId))
        ).andExpect(status().isOk)

        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))
        assertEquals(1, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `POST unread moves archive post to unread`() {
        val postId = insertArchivePost()

        mockMvc.perform(
            post("/api/v1/posts/$postId/unread").with(withAuth(userId))
        ).andExpect(status().isOk)

        assertEquals(1, postDao.getPostCount(userId, PostType.UNREAD))
        assertEquals(0, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `POST favorite toggles favorite and returns updated card`() {
        val postId = insertPost()

        // Toggle on
        mockMvc.perform(
            post("/api/v1/posts/$postId/favorite")
                .param("type", "unread")
                .with(withAuth(userId))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("aria-pressed=\"true\"")))

        // Toggle off
        mockMvc.perform(
            post("/api/v1/posts/$postId/favorite")
                .param("type", "unread")
                .with(withAuth(userId))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("aria-pressed=\"false\"")))
    }

    @Test
    fun `DELETE post removes it from unread`() {
        val postId = insertPost()

        mockMvc.perform(
            delete("/api/v1/posts/$postId")
                .param("type", "unread")
                .with(withAuth(userId))
        ).andExpect(status().isOk)

        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `DELETE post removes it from archive`() {
        val postId = insertArchivePost()

        mockMvc.perform(
            delete("/api/v1/posts/$postId")
                .param("type", "archive")
                .with(withAuth(userId))
        ).andExpect(status().isOk)

        assertEquals(0, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `GET cover-image proxies the bytes with the upstream content type`() {
        stubImage("/pic-basic.png")
        val postId = insertPostWithOgImage(imageUrl("/pic-basic.png"))

        mockMvc.perform(get("/api/v1/posts/$postId/cover-image").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(content().bytes(pngBytes))
    }

    @Test
    fun `GET cover-image hits the origin once and serves the rest from cache`() {
        stubImage("/pic-cached.png")
        val postId = insertPostWithOgImage(imageUrl("/pic-cached.png"))

        repeat(3) {
            mockMvc.perform(get("/api/v1/posts/$postId/cover-image").with(withAuth(userId)))
                .andExpect(status().isOk)
        }

        BaseIntegrationTest.wireMockTelegramApi.verify(1, getRequestedFor(urlEqualTo("/pic-cached.png")))
    }

    @Test
    fun `GET cover-image refuses svg, which would be a script document on our origin`() {
        stubImage("/pic.svg", contentType = "image/svg+xml", body = "<svg/>".toByteArray())
        val postId = insertPostWithOgImage(imageUrl("/pic.svg"))

        mockMvc.perform(get("/api/v1/posts/$postId/cover-image").with(withAuth(userId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET cover-image refuses a non-image content type`() {
        stubImage("/not-a-pic", contentType = "text/html", body = "<html></html>".toByteArray())
        val postId = insertPostWithOgImage(imageUrl("/not-a-pic"))

        mockMvc.perform(get("/api/v1/posts/$postId/cover-image").with(withAuth(userId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET cover-image returns 404 when the post carries no og image`() {
        val postId = insertPost()

        mockMvc.perform(get("/api/v1/posts/$postId/cover-image").with(withAuth(userId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET cover-image on another user's post returns 404`() {
        stubImage("/pic-victim.png")
        val victimPostId = insertPostWithOgImage(imageUrl("/pic-victim.png"), owner = otherUserId)

        mockMvc.perform(get("/api/v1/posts/$victimPostId/cover-image").with(withAuth(userId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET cover-image without auth returns 401`() {
        mockMvc.perform(get("/api/v1/posts/1/cover-image"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST archive returns the new archive id so the undo toast can send it back`() {
        val postId = insertPost(title = "To Archive", url = "https://toarchive.com")

        val body = mockMvc.perform(post("/api/v1/posts/$postId/archive").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val archivedId = Regex("\"archivedId\"\\s*:\\s*(\\d+)").find(body)!!.groupValues[1].toLong()

        // The returned id is the one undo posts to, so it must really move the post back.
        mockMvc.perform(post("/api/v1/posts/$archivedId/unread").with(withAuth(userId)))
            .andExpect(status().isOk)

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM post WHERE user_id = ? AND url = ?",
                Int::class.java, userId, "https://toarchive.com"
            )
        )
    }

    @Test
    fun `GET og-image returns ogImageUrl when og tag present`() {
        val articlePath = "/og-article"
        val postId = insertPost(url = "http://localhost:${BaseIntegrationTest.wireMockTelegramApi.port()}$articlePath")

        BaseIntegrationTest.wireMockTelegramApi.stubFor(
            wmGet(urlEqualTo(articlePath)).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=UTF-8")
                    .withBody("""<html><head><meta property="og:image" content="https://example.com/thumb.jpg"/></head><body></body></html>""")
            )
        )

        mockMvc.perform(
            get("/api/v1/posts/$postId/og-image")
                .param("type", "unread")
                .with(withAuth(userId))
        )
            .andExpect(status().isOk)
            .andExpect(content().json("""{"ogImageUrl":"https://example.com/thumb.jpg"}"""))
    }

    @Test
    fun `GET og-image returns 404 when no og tag`() {
        val articlePath = "/no-og-article"
        val postId = insertPost(url = "http://localhost:${BaseIntegrationTest.wireMockTelegramApi.port()}$articlePath")

        BaseIntegrationTest.wireMockTelegramApi.stubFor(
            wmGet(urlEqualTo(articlePath)).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=UTF-8")
                    .withBody("<html><head><title>No OG</title></head><body></body></html>")
            )
        )

        mockMvc.perform(
            get("/api/v1/posts/$postId/og-image")
                .param("type", "unread")
                .with(withAuth(userId))
        )
            .andExpect(status().isNotFound)
    }

    // --- Authorization (IDOR) — a user must not touch another user's posts ---

    @Test
    fun `DELETE another user's post returns 404 and leaves it intact`() {
        val victimPostId = insertPostFor(userId, url = "https://victim.com")

        mockMvc.perform(
            delete("/api/v1/posts/$victimPostId")
                .param("type", "unread")
                .with(withAuth(otherUserId))
        ).andExpect(status().isNotFound)

        assertEquals(1, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `POST archive on another user's post returns 404 and does not move it`() {
        val victimPostId = insertPostFor(userId, url = "https://victim.com")

        mockMvc.perform(
            post("/api/v1/posts/$victimPostId/archive").with(withAuth(otherUserId))
        ).andExpect(status().isNotFound)

        assertEquals(1, postDao.getPostCount(userId, PostType.UNREAD))
        assertEquals(0, postDao.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `POST unread on another user's archive post returns 404 and does not move it`() {
        val victimPostId = insertArchivePostFor(userId, url = "https://victim.com")

        mockMvc.perform(
            post("/api/v1/posts/$victimPostId/unread").with(withAuth(otherUserId))
        ).andExpect(status().isNotFound)

        assertEquals(1, postDao.getPostCount(userId, PostType.ARCHIVE))
        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `POST favorite on another user's post returns 404 and does not toggle it`() {
        val victimPostId = insertPostFor(userId, url = "https://victim.com")

        mockMvc.perform(
            post("/api/v1/posts/$victimPostId/favorite")
                .param("type", "unread")
                .with(withAuth(otherUserId))
        ).andExpect(status().isNotFound)

        val isFavorite = jdbcTemplate.queryForObject(
            "SELECT is_favorite FROM post WHERE id = ?", Boolean::class.java, victimPostId
        )
        assertEquals(false, isFavorite)
    }

    @Test
    fun `GET og-image on another user's post returns 404 and does not disclose it`() {
        val articlePath = "/victim-og"
        val victimUrl = "http://localhost:${BaseIntegrationTest.wireMockTelegramApi.port()}$articlePath"
        val victimPostId = insertPostFor(userId, url = victimUrl)

        // Even though the URL would yield an og:image, a non-owner must get 404.
        BaseIntegrationTest.wireMockTelegramApi.stubFor(
            wmGet(urlEqualTo(articlePath)).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=UTF-8")
                    .withBody("""<html><head><meta property="og:image" content="https://example.com/secret.jpg"/></head></html>""")
            )
        )

        mockMvc.perform(
            get("/api/v1/posts/$victimPostId/og-image")
                .param("type", "unread")
                .with(withAuth(otherUserId))
        ).andExpect(status().isNotFound)
    }
}
