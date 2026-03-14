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
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import java.time.Duration
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.proshik.pochitushki.BaseIntegrationTest

@ActiveProfiles("test", "telegram-test")
class TelegramBotIntegrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var wireMockExternalUrl: WireMockServer

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
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

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
        // Register user via /start
        postUpdate(buildStartUpdate(updateId = 10, messageId = 10))
        awaitUserCreated()

        wireMockTelegramApi.resetAll()
        setupTelegramApiStubs()

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
        postUpdate("""
            {
              "update_id": 20,
              "message": {
                "message_id": 20,
                "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
                "chat": {"id": 100, "type": "private"},
                "text": "$articleUrl"
              }
            }
        """.trimIndent())

        awaitPostCreated()

        // Jsoup fetched the external page
        wireMockExternalUrl.verify(getRequestedFor(urlEqualTo("/article")))

        // Bot sent confirmation via Telegram API
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )

        // Post saved in DB with correct title
        val posts = jdbcTemplate.queryForList("SELECT title, url FROM post WHERE url LIKE ?", "%/article%")
        assert(posts.isNotEmpty()) { "Expected post to be saved in DB" }
        assert(posts[0]["title"] == "Test Article Title") {
            "Expected title 'Test Article Title' but got '${posts[0]["title"]}'"
        }
    }

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
}
