package ru.proshik.pochitushki.telegram

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import java.time.Duration
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.service.I18nService

/** Widgets need webhook mode, so this class runs the bot behind its own webhook URL. */
@AutoConfigureTestRestTemplate
@ActiveProfiles("test", "telegram-test", "telegram-widgets-test")
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
class TelegramWidgetIntegrationTest : BaseIntegrationTest() {

    @Autowired private lateinit var restTemplate: TestRestTemplate
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var i18nService: I18nService

    companion object {
        private val ids = java.util.concurrent.atomic.AtomicInteger(50_000)

        private fun ok(body: String) =
            aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

        @JvmStatic
        @DynamicPropertySource
        fun webhook(registry: DynamicPropertyRegistry) {
            registry.add("telegram.webhook-url") { "https://example.test/test_token" }
            // Runs right before this context starts, i.e. after other classes' resetAll():
            // startWebhook() calls setWebhook at startup and refuses to start without "ok".
            wireMockTelegramApi.stubFor(post(urlEqualTo("/bottest_token/setWebhook")).willReturn(ok("""{"ok":true,"result":true}""")))
        }
    }

    private val message = """{"ok":true,"result":{"message_id":1,"chat":{"id":100,"type":"private"},"text":"ok","date":0}}"""

    @BeforeEach
    fun setUp() {
        wireMockTelegramApi.stubFor(post(anyUrl()).willReturn(ok(message)))
        wireMockTelegramApi.stubFor(get(anyUrl()).willReturn(ok("""{"ok":true,"result":[]}""")))
        jdbcTemplate.update(
            """INSERT INTO users(telegram_id, username, first_name, settings)
               VALUES (100, 'ivan_test', 'Ivan', '{"languageCode":"en"}'::jsonb)"""
        )
    }

    @AfterEach
    fun tearDown() {
        wireMockTelegramApi.resetAll()
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    private fun userId(): Long = jdbcTemplate.queryForObject("SELECT id FROM users WHERE telegram_id = 100", Long::class.java)!!

    private fun createPost(title: String, url: String): Long = jdbcTemplate.queryForObject(
        "INSERT INTO post (title, url, user_id) VALUES (?, ?, ?) RETURNING id", Long::class.java, title, url, userId()
    )!!

    private fun postUpdate(json: String) {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Telegram-Bot-Api-Secret-Token", "test-secret")
        }
        restTemplate.postForEntity("/test_token", HttpEntity(json, headers), String::class.java)
    }

    private fun bodies(method: String): List<String> =
        wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/$method"))).map { it.bodyAsString }

    private fun awaitBody(method: String): String {
        await().atMost(Duration.ofSeconds(5)).until { bodies(method).isNotEmpty() }
        return bodies(method).first()
    }

    private fun command(text: String) = """
        {"update_id": ${ids.incrementAndGet()}, "message": {"message_id": ${ids.get()},
          "from": {"id": 100, "first_name": "Ivan", "is_bot": false, "language_code": "en"},
          "chat": {"id": 100, "type": "private"}, "text": "/$text",
          "entities": [{"type": "bot_command", "offset": 0, "length": ${text.length + 1}}]}}
    """.trimIndent()

    private fun widgetQuery(queryId: String, data: String, messageId: Long = 88) = """
        {"update_id": ${ids.incrementAndGet()}, "widget_query": {"id": "$queryId",
          "from": {"id": 100, "first_name": "Ivan", "is_bot": false, "language_code": "en"},
          "chat": {"id": 100, "type": "private"}, "message_id": $messageId, "chat_instance": "1",
          "type": {"type": "data"}, "data": "$data"}}
    """.trimIndent()

    @Test
    @org.junit.jupiter.api.Order(1)
    fun `webhook asks for widget updates`() {
        // setWebhook runs once, at context start; this test runs first (@Order), before any
        // tearDown of this class resets the request journal.
        val setWebhook = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/setWebhook")))
            .map { java.net.URLDecoder.decode(it.bodyAsString, Charsets.UTF_8) }
        assertEquals(1, setWebhook.size, "setWebhook at startup")
        // Form-encoded by the library: allowed_updates=message&allowed_updates=callback_query&…
        val body = setWebhook.single()
        for (type in listOf("message", "callback_query", "widget_query", "shared_widget_query")) {
            assertTrue(body.contains("allowed_updates=$type&") || body.endsWith("allowed_updates=$type"), "$type in $body")
        }
    }

    @Test
    fun `refused widget redraw keeps the old buttons`() {
        val p = createPost("A", "https://a.com")
        createPost("B", "https://b.com")
        wireMockTelegramApi.stubFor(
            post(urlEqualTo("/bottest_token/editMessageWidgetData")).willReturn(
                aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":false,"error_code":400,"description":"Bad Request: message to edit not found"}""")
            )
        )

        postUpdate(widgetQuery("q5", "cn|U:1:$p:u"))

        val answer = awaitBody("answerWidgetQuery")
        assertTrue(answer.contains(i18nService.getMessage("card.toast.error", "en")), answer)
        assertTrue(bodies("editMessageReplyMarkup").isEmpty(), "buttons must not move to the other post")
    }

    @Test
    fun `feed sends a widget`() {
        val p = createPost("Widget post", "https://widget.com")

        postUpdate(command("feed"))

        val body = awaitBody("sendWidget")
        assertTrue(body.contains("\"allow_queries\":true"), body)
        assertTrue(body.contains("U:0:$p:u"), body)
        assertTrue(body.contains("cp|U:0:$p:u"), "PDF/labels/app under the widget: $body")
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until { bodies("sendMessage").isEmpty() }
    }

    @Test
    fun `widget refusal falls back to the text card`() {
        createPost("Fallback", "https://fallback.com")
        wireMockTelegramApi.stubFor(
            post(urlEqualTo("/bottest_token/sendWidget")).willReturn(
                aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":false,"error_code":400,"description":"Bad Request: WIDGETS_NOT_ALLOWED"}""")
            )
        )

        postUpdate(command("feed"))

        val text = awaitBody("sendMessage")
        assertTrue(text.contains(">Fallback</a>") && text.contains("ca|u:0:"), text)
    }

    @Test
    fun `widget archive updates data and answers`() {
        val p = createPost("Archive me", "https://archive-me.com")
        createPost("Next", "https://next.com")

        postUpdate(widgetQuery("q1", "ca|U:0:$p:u"))

        val data = awaitBody("editMessageWidgetData")
        assertTrue(data.contains("\"message_id\":88"), data)
        val answer = awaitBody("answerWidgetQuery")
        assertTrue(answer.contains("\"widget_query_id\":\"q1\""), answer)
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM archive_post", Int::class.java))
    }

    @Test
    fun `stale widget query answers with the gone toast`() {
        createPost("Current", "https://current.com")

        postUpdate(widgetQuery("q2", "ca|U:0:999999:u"))

        val answer = awaitBody("answerWidgetQuery")
        assertTrue(answer.contains(i18nService.getMessage("card.toast.gone", "en")), answer)
    }

    @Test
    fun `garbage widget data is still answered`() {
        postUpdate(widgetQuery("q3", "zz"))

        assertEquals("""{"widget_query_id":"q3"}""", awaitBody("answerWidgetQuery"))
    }

    @Test
    fun `shared widget query is answered`() {
        postUpdate(
            """{"update_id": ${ids.incrementAndGet()}, "shared_widget_query": {"id": "q4",
              "from": {"id": 100, "first_name": "Ivan", "is_bot": false}, "type": {"type": "data"},
              "data": "ca|U:0:1:u", "widget_id": "w"}}"""
        )

        assertEquals("""{"widget_query_id":"q4"}""", awaitBody("answerWidgetQuery"))
    }

    @Test
    fun `labels back under a widget restores the widget keyboard`() {
        val p = createPost("Labelled", "https://labelled.com")

        postUpdate(
            """{"update_id": ${ids.incrementAndGet()}, "callback_query": {"id": "cb1",
              "from": {"id": 100, "first_name": "Ivan", "is_bot": false},
              "message": {"message_id": 88, "chat": {"id": 100, "type": "private"}, "date": 0},
              "chat_instance": "1", "data": "lb|U:0:$p:u"}}"""
        )

        val keyboard = awaitBody("editMessageReplyMarkup")
        assertTrue(keyboard.contains("cp|U:0:$p:u") && !keyboard.contains("cn|"), keyboard)
    }
}
