package ru.proshik.pochitushki.controller

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.service.LabelService

@AutoConfigureMockMvc
class LabelApiControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var labelService: LabelService

    private var userId: Long = 0L
    private var otherUserId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = insertUser(5201, "labelapi")
        otherUserId = insertUser(5202, "labelapiother")
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

    private fun insertUser(telegramId: Long, username: String): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (?, ?, 'Label', 'Api', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java, telegramId, username
        )!!

    private fun insertPost(title: String, url: String, owner: Long = userId): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, owner
        )!!

    @Test
    fun `POST labels creates a label`() {
        mockMvc.perform(
            post("/api/v1/labels")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"kotlin"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("kotlin"))
    }

    @Test
    fun `POST labels normalises the name so one label does not become three`() {
        mockMvc.perform(
            post("/api/v1/labels")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"  #kotlin  "}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("kotlin"))

        mockMvc.perform(
            post("/api/v1/labels")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"kotlin"}""")
        ).andExpect(status().isOk)

        assertEquals(1, labelService.getLabels(userId).size)
    }

    @Test
    fun `POST labels rejects a blank name`() {
        mockMvc.perform(
            post("/api/v1/labels")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"   "}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST labels rejects an over-long name`() {
        val tooLong = "x".repeat(LabelService.MAX_NAME_LENGTH + 1)

        mockMvc.perform(
            post("/api/v1/labels")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$tooLong"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET labels lists only the caller's labels`() {
        labelService.createLabel(userId, "mine")
        labelService.createLabel(otherUserId, "theirs")

        mockMvc.perform(get("/api/v1/labels").with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("mine")))
            .andExpect(content().string(not(containsString("theirs"))))
    }

    @Test
    fun `DELETE labels removes the caller's label`() {
        val label = labelService.createLabel(userId, "kotlin")

        mockMvc.perform(delete("/api/v1/labels/${label.id}").with(withAuth(userId)))
            .andExpect(status().isOk)

        assertEquals(0, labelService.getLabels(userId).size)
    }

    @Test
    fun `DELETE labels on another user's label returns 404 and leaves it alone`() {
        val theirs = labelService.createLabel(otherUserId, "theirs")

        mockMvc.perform(delete("/api/v1/labels/${theirs.id}").with(withAuth(userId)))
            .andExpect(status().isNotFound)

        assertEquals(1, labelService.getLabels(otherUserId).size)
    }

    @Test
    fun `POST post labels attaches the label`() {
        val postId = insertPost("Post", "https://labelled.com")
        val label = labelService.createLabel(userId, "kotlin")

        mockMvc.perform(post("/api/v1/posts/$postId/labels/${label.id}").with(withAuth(userId)))
            .andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/posts").param("labelId", label.id.toString()).with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("https://labelled.com")))
    }

    @Test
    fun `attaching a label that is not yours returns 404`() {
        val postId = insertPost("Post", "https://mine.com")
        val theirLabel = labelService.createLabel(otherUserId, "theirs")

        mockMvc.perform(post("/api/v1/posts/$postId/labels/${theirLabel.id}").with(withAuth(userId)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `attaching your label to someone else's post writes nothing`() {
        val theirPostId = insertPost("Theirs", "https://nottouching.com", owner = otherUserId)
        val label = labelService.createLabel(userId, "kotlin")

        mockMvc.perform(post("/api/v1/posts/$theirPostId/labels/${label.id}").with(withAuth(userId)))
            .andExpect(status().isOk)

        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT count(*) FROM post_label", Int::class.java)
        )
    }

    @Test
    fun `attaching the same label twice stays 200`() {
        val postId = insertPost("Post", "https://twice.com")
        val label = labelService.createLabel(userId, "kotlin")

        repeat(2) {
            mockMvc.perform(post("/api/v1/posts/$postId/labels/${label.id}").with(withAuth(userId)))
                .andExpect(status().isOk)
        }
    }

    @Test
    fun `DELETE post labels detaches without touching the label itself`() {
        val postId = insertPost("Post", "https://detach.com")
        val label = labelService.createLabel(userId, "kotlin")
        labelService.attachLabel(postId, label.id, userId, LabelTarget.UNREAD)

        mockMvc.perform(delete("/api/v1/posts/$postId/labels/${label.id}").with(withAuth(userId)))
            .andExpect(status().isOk)

        assertEquals(1, labelService.getLabels(userId).size)
        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT count(*) FROM post_label", Int::class.java)
        )
    }

    @Test
    fun `GET posts by label covers both shelves and skips other labels`() {
        val label = labelService.createLabel(userId, "kotlin")
        val unreadId = insertPost("Unread one", "https://unread-labelled.com")
        val archiveId = jdbcTemplate.queryForObject(
            "INSERT INTO archive_post(title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, "Archived one", "https://archive-labelled.com", userId
        )!!
        insertPost("Unlabelled", "https://unlabelled.com")
        labelService.attachLabel(unreadId, label.id, userId, LabelTarget.UNREAD)
        labelService.attachLabel(archiveId, label.id, userId, LabelTarget.ARCHIVE)

        mockMvc.perform(get("/api/v1/posts").param("labelId", label.id.toString()).with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("https://unread-labelled.com")))
            .andExpect(content().string(containsString("https://archive-labelled.com")))
            .andExpect(content().string(not(containsString("https://unlabelled.com"))))
    }

    @Test
    fun `GET posts by another user's label returns nothing`() {
        val theirLabel = labelService.createLabel(otherUserId, "theirs")
        val theirPostId = insertPost("Theirs", "https://theirpost.com", owner = otherUserId)
        labelService.attachLabel(theirPostId, theirLabel.id, otherUserId, LabelTarget.UNREAD)

        mockMvc.perform(get("/api/v1/posts").param("labelId", theirLabel.id.toString()).with(withAuth(userId)))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("https://theirpost.com"))))
    }

    @Test
    fun `PATCH labels renames`() {
        val label = labelService.createLabel(userId, "kotln")

        mockMvc.perform(
            patch("/api/v1/labels/${label.id}")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"kotlin"}""")
        ).andExpect(status().isOk)

        assertEquals(listOf("kotlin"), labelService.getLabels(userId).map { it.name })
    }

    @Test
    fun `PATCH labels refuses to merge onto a name you already have`() {
        labelService.createLabel(userId, "kotlin")
        val other = labelService.createLabel(userId, "spring")

        mockMvc.perform(
            patch("/api/v1/labels/${other.id}")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"kotlin"}""")
        ).andExpect(status().isConflict)

        // both survive, unchanged
        assertEquals(listOf("kotlin", "spring"), labelService.getLabels(userId).map { it.name })
    }

    @Test
    fun `PATCH labels rejects a blank name and another user's label`() {
        val mine = labelService.createLabel(userId, "mine")
        val theirs = labelService.createLabel(otherUserId, "theirs")

        mockMvc.perform(
            patch("/api/v1/labels/${mine.id}")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"  "}""")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            patch("/api/v1/labels/${theirs.id}")
                .with(withAuth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"stolen"}""")
        ).andExpect(status().isNotFound)

        assertEquals(listOf("theirs"), labelService.getLabels(otherUserId).map { it.name })
    }

    @Test
    fun `label counts span both shelves and survive a label with no posts`() {
        val used = labelService.createLabel(userId, "used")
        labelService.createLabel(userId, "unused")
        val unreadId = insertPost("U", "https://u.com")
        val archiveId = jdbcTemplate.queryForObject(
            "INSERT INTO archive_post(title, url, user_id) VALUES ('A', 'https://a.com', ?) RETURNING id",
            Long::class.java, userId
        )!!
        labelService.attachLabel(unreadId, used.id, userId, LabelTarget.UNREAD)
        labelService.attachLabel(archiveId, used.id, userId, LabelTarget.ARCHIVE)

        val counts = labelService.getLabelsWithCounts(userId).associate { it.name to it.postCount }

        assertEquals(2, counts["used"])
        assertEquals(0, counts["unused"])
    }

    @Test
    fun `label endpoints without auth return 401`() {
        mockMvc.perform(get("/api/v1/labels")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/api/v1/labels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"kotlin"}""")
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/api/v1/labels/1")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/v1/posts/1/labels/1")).andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/api/v1/posts/1/labels/1")).andExpect(status().isUnauthorized)
    }
}
