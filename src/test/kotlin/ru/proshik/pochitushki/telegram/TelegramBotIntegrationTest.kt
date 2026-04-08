package ru.proshik.pochitushki.telegram

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.time.Duration
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.PostStoreData

@ActiveProfiles("test", "telegram-test")
class TelegramBotIntegrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var wireMockExternalUrl: WireMockServer

    private var updateIdCounter = 100

    @BeforeEach
    fun setUp() {
        setupTelegramApiStubs()

        wireMockExternalUrl = WireMockServer(wireMockConfig().dynamicPort())
        wireMockExternalUrl.start()
    }

    @AfterEach
    fun tearDown() {
        wireMockExternalUrl.stop()
        wireMockTelegramApi.resetAll()
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    // ==================== Command tests ====================

    @Test
    fun `start command should call sendMessage with chat_id`() {
        postUpdate(buildStartUpdate(updateId = 1, messageId = 1))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `url message should fetch title, save post and confirm via sendMessage`() {
        registerTestUser()

        // External URL stub
        wireMockExternalUrl.stubFor(
            get(urlEqualTo("/article")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody("<html><head><title>Test Article Title</title></head><body>Content</body></html>")
            )
        )

        val articleUrl = "http://localhost:${wireMockExternalUrl.port()}/article"
        postUpdate(buildTextMessage(nextUpdateId(), articleUrl))

        awaitPostCreated()

        wireMockExternalUrl.verify(getRequestedFor(urlEqualTo("/article")))
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )

        val posts = jdbcTemplate.queryForList("SELECT title, url FROM post WHERE url LIKE ?", "%/article%")
        assert(posts.isNotEmpty()) { "Expected post to be saved in DB" }
        assert(posts[0]["title"] == "Test Article Title") {
            "Expected title 'Test Article Title' but got '${posts[0]["title"]}'"
        }
    }

    @Test
    fun `help command should send help message`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/help", "help"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `feed command should send feed or not-found message`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/feed", "feed"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `feed command with posts should show posts`() {
        registerTestUser()
        val userId = getUserId()
        createPost(userId, "Feed Post", "https://feed-test.com")

        postUpdate(buildCommandMessage(nextUpdateId(), "/feed", "feed"))

        // With 1 post: header message + 1 post message = 2 sendMessage calls
        awaitTelegramApiCalls("/bottest_token/sendMessage", 2)
    }

    @Test
    fun `feed command with posts should include date in message body`() {
        registerTestUser()
        val userId = getUserId()
        createPost(userId, "Dated Post", "https://dated-post.com")

        postUpdate(buildCommandMessage(nextUpdateId(), "/feed", "feed"))

        awaitTelegramApiCalls("/bottest_token/sendMessage", 2)

        val calls = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendMessage")))
        // The bot sends form-encoded requests: MarkdownV2 backslashes (\) are URL-encoded to %5C
        // So date "05\.04\.2026" appears as "05%5C.04%5C.2026" in the raw body
        val hasDate = calls.any { call ->
            call.bodyAsString.contains(Regex("""\d{2}%5C\.\d{2}%5C\.\d{4}"""))
        }
        assertTrue(hasDate, "Expected post card to contain a URL-encoded date in dd%5C.MM%5C.yyyy format")
    }

    @Test
    fun `archive command should send archive feed or not-found`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/archive", "archive"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `favorites command should send favorites feed or not-found`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/favorites", "favorites"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `random_post command should send random post or not-found`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/random_post", "random_post"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `random_post command with posts should show a post`() {
        registerTestUser()
        val userId = getUserId()
        createPost(userId, "Random Post", "https://random-test.com")

        postUpdate(buildCommandMessage(nextUpdateId(), "/random_post", "random_post"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `profile command should send profile settings`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/profile", "profile"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `invalid URL should send error message`() {
        registerTestUser()

        postUpdate(buildTextMessage(nextUpdateId(), "not-a-valid-url"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    // ==================== Callback query tests ====================

    @Test
    fun `archive_button callback should archive post and delete message`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "To Archive", "https://archive-cb.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 50L, "archive_button|$postId"))

        awaitCondition { getPostCount("post") == 0 && getPostCount("archive_post") == 1 }
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/deleteMessage"))
        )
    }

    @Test
    fun `delete_button callback should delete unread post and delete message`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "To Delete", "https://delete-cb.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 51L, "delete_button|$postId"))

        awaitCondition { getPostCount("post") == 0 }
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/deleteMessage"))
        )
    }

    @Test
    fun `unread_button callback should move post from archive to unread`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "To Unread", "https://unread-cb.com")
        archivePost(postId)
        val archivePostId = getArchivePostId(userId)

        postUpdate(buildCallbackQuery(nextUpdateId(), 52L, "unread_button|$archivePostId"))

        awaitCondition { getPostCount("post") == 1 && getPostCount("archive_post") == 0 }
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/deleteMessage"))
        )
    }

    @Test
    fun `delete_archive_button callback should delete archive post`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Del Archive", "https://del-archive-cb.com")
        archivePost(postId)
        val archivePostId = getArchivePostId(userId)

        postUpdate(buildCallbackQuery(nextUpdateId(), 53L, "delete_archive_button|$archivePostId"))

        awaitCondition { getPostCount("archive_post") == 0 }
    }

    @Test
    fun `toggle_unread_favorite callback should toggle favorite and edit message`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Fav Toggle", "https://fav-toggle.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 54L, "toggle_unread_favorite|$postId"))

        awaitCondition {
            jdbcTemplate.queryForObject(
                "SELECT is_favorite FROM post WHERE id = ?", Boolean::class.java, postId
            ) == true
        }
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/editMessageText"))
        )
    }

    @Test
    fun `toggle_archive_favorite callback should toggle archive favorite`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Arch Fav", "https://arch-fav.com")
        archivePost(postId)
        val archivePostId = getArchivePostId(userId)

        postUpdate(buildCallbackQuery(nextUpdateId(), 55L, "toggle_archive_favorite|$archivePostId"))

        awaitCondition {
            jdbcTemplate.queryForObject(
                "SELECT is_favorite FROM archive_post WHERE id = ?", Boolean::class.java, archivePostId
            ) == true
        }
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/editMessageText"))
        )
    }

    @Test
    fun `next_posts callback should show next page of posts`() {
        registerTestUser()
        val userId = getUserId()
        for (i in 1..5) {
            createPost(userId, "Post $i", "https://page-$i.com")
        }

        postUpdate(buildCallbackQuery(nextUpdateId(), 56L, "next_posts|3"))

        awaitTelegramApiCalls("/bottest_token/sendMessage", 2)
    }

    @Test
    fun `previous_posts callback should show previous page`() {
        registerTestUser()
        val userId = getUserId()
        for (i in 1..6) {
            createPost(userId, "Post $i", "https://prev-page-$i.com")
        }

        postUpdate(buildCallbackQuery(nextUpdateId(), 57L, "previous_posts|3"))

        awaitTelegramApiCalls("/bottest_token/sendMessage", 2)
    }

    @Test
    fun `next_random_post callback should show next random post`() {
        registerTestUser()
        val userId = getUserId()
        createPost(userId, "Random 1", "https://random1.com")
        createPost(userId, "Random 2", "https://random2.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 58L, "next_random_post|_"))

        awaitEditMessage()
    }

    @Test
    fun `random_archive_button callback should archive random post and show next`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Rand Arch", "https://rand-arch.com")
        createPost(userId, "Rand Other", "https://rand-other.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 59L, "random_archive_button|$postId"))

        awaitCondition { getPostCount("archive_post") == 1 }
    }

    @Test
    fun `random_delete_button callback should delete random post and show next`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Rand Del", "https://rand-del.com")
        createPost(userId, "Rand Keep", "https://rand-keep.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 60L, "random_delete_button|$postId"))

        awaitCondition { getPostCount("post") == 1 }
    }

    @Test
    fun `profile_feed_settings callback should show feed settings`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 61L, "profile_feed_settings|_"))

        awaitEditMessage()
    }

    @Test
    fun `profile_language_settings callback should show language settings`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 62L, "profile_language_settings|_"))

        awaitEditMessage()
    }

    @Test
    fun `profile_language_settings_changed callback should update language`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 63L, "profile_language_settings_changed|ru"))

        awaitCondition {
            val settings = jdbcTemplate.queryForObject(
                "SELECT settings::TEXT FROM users WHERE telegram_id = 100", String::class.java
            )
            settings != null && settings.contains("ru")
        }
    }

    @Test
    fun `profile_feed_settings_changed callback should update feed count`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 64L, "profile_feed_settings_changed|5"))

        awaitCondition {
            val settings = jdbcTemplate.queryForObject(
                "SELECT settings::TEXT FROM users WHERE telegram_id = 100", String::class.java
            )
            settings != null && settings.contains("5")
        }
    }

    @Test
    fun `profile_back_button_settings callback should show profile`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 65L, "profile_back_button_settings|_"))

        awaitSendMessage()
    }

    @Test
    fun `favorites_to_archive callback should archive post and edit message keyboard, not delete`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Fav To Archive", "https://fav-to-archive.com")
        markAsFavorite(postId)

        postUpdate(buildCallbackQuery(nextUpdateId(), 70L, "favorites_to_archive|$postId"))

        awaitCondition { getPostCount("post") == 0 && getPostCount("archive_post") == 1 }
        awaitEditMessage()
        // Ensure message was NOT deleted
        wireMockTelegramApi.verify(
            0, postRequestedFor(urlEqualTo("/bottest_token/deleteMessage"))
        )
    }

    @Test
    fun `favorites_to_unread callback should move post to unread and edit message keyboard, not delete`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Fav To Unread", "https://fav-to-unread.com")
        markAsFavorite(postId)
        archivePost(postId)
        val archivePostId = getArchivePostId(userId)
        markArchiveAsFavorite(archivePostId)

        postUpdate(buildCallbackQuery(nextUpdateId(), 71L, "favorites_to_unread|$archivePostId"))

        awaitCondition { getPostCount("post") == 1 && getPostCount("archive_post") == 0 }
        awaitEditMessage()
        wireMockTelegramApi.verify(
            0, postRequestedFor(urlEqualTo("/bottest_token/deleteMessage"))
        )
    }

    // ==================== Helper methods ====================

    private fun setupTelegramApiStubs() {
        wireMockTelegramApi.stubFor(
            post(anyUrl()).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":true,"result":{"message_id":1,"chat":{"id":100,"type":"private"},"text":"ok","date":0}}""")
            )
        )
        wireMockTelegramApi.stubFor(
            get(anyUrl()).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":true,"result":[]}""")
            )
        )
    }

    private fun registerTestUser() {
        postUpdate(buildStartUpdate(updateId = nextUpdateId(), messageId = nextUpdateId()))
        awaitUserCreated()
        wireMockTelegramApi.resetAll()
        setupTelegramApiStubs()
    }

    private fun nextUpdateId(): Int = updateIdCounter++

    private fun getUserId(): Long {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE telegram_id = 100", Long::class.java
        )!!
    }

    private fun createPost(userId: Long, title: String, url: String): Long {
        return jdbcTemplate.queryForObject(
            "INSERT INTO post (title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, userId
        )!!
    }

    private fun markAsFavorite(postId: Long) {
        jdbcTemplate.update("UPDATE post SET is_favorite = true WHERE id = ?", postId)
    }

    private fun markArchiveAsFavorite(archivePostId: Long) {
        jdbcTemplate.update("UPDATE archive_post SET is_favorite = true WHERE id = ?", archivePostId)
    }

    private fun archivePost(postId: Long) {
        jdbcTemplate.update(
            """INSERT INTO archive_post(title, url, tags, user_id, is_favorite)
               SELECT title, url, tags, user_id, is_favorite FROM post WHERE id = ?""", postId
        )
        jdbcTemplate.update("DELETE FROM post WHERE id = ?", postId)
    }

    private fun getArchivePostId(userId: Long): Long {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!
    }

    private fun getPostCount(table: String): Int {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
    }

    private fun postUpdate(json: String) {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        restTemplate.postForEntity("/test_token", HttpEntity(json, headers), String::class.java)
    }

    private fun awaitSendMessage() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            wireMockTelegramApi.verify(
                postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
            )
        }
    }

    private fun awaitEditMessage() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            wireMockTelegramApi.verify(
                postRequestedFor(urlEqualTo("/bottest_token/editMessageText"))
            )
        }
    }

    private fun awaitTelegramApiCalls(url: String, minCount: Int) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val count = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo(url))).size
            assertTrue(count >= minCount, "Expected at least $minCount calls to $url but got $count")
        }
    }

    private fun awaitUserCreated() {
        await().atMost(Duration.ofSeconds(5)).until {
            jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE telegram_id = 100", Int::class.java)!! > 0
        }
    }

    private fun awaitPostCreated() {
        await().atMost(Duration.ofSeconds(5)).until {
            jdbcTemplate.queryForObject("SELECT count(*) FROM post WHERE url LIKE '%/article%'", Int::class.java)!! > 0
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        await().atMost(Duration.ofSeconds(5)).until { condition() }
    }

    private fun buildStartUpdate(updateId: Int, messageId: Int): String = """
        {
          "update_id": $updateId,
          "message": {
            "message_id": $messageId,
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "chat": {"id": 100, "type": "private"},
            "text": "/start",
            "entities": [{"type": "bot_command", "offset": 0, "length": 6}]
          }
        }
    """.trimIndent()

    private fun buildCommandMessage(updateId: Int, text: String, command: String): String = """
        {
          "update_id": $updateId,
          "message": {
            "message_id": $updateId,
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "chat": {"id": 100, "type": "private"},
            "text": "$text",
            "entities": [{"type": "bot_command", "offset": 0, "length": ${text.length}}]
          }
        }
    """.trimIndent()

    private fun buildTextMessage(updateId: Int, text: String): String = """
        {
          "update_id": $updateId,
          "message": {
            "message_id": $updateId,
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "chat": {"id": 100, "type": "private"},
            "text": "$text"
          }
        }
    """.trimIndent()

    private fun buildCallbackQuery(updateId: Int, messageId: Long, callbackData: String): String = """
        {
          "update_id": $updateId,
          "callback_query": {
            "id": "$updateId",
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "message": {
              "message_id": $messageId,
              "from": {"id": 999, "first_name": "Bot", "is_bot": true},
              "chat": {"id": 100, "type": "private"},
              "text": "some message",
              "date": 0
            },
            "data": "$callbackData"
          }
        }
    """.trimIndent()
}
