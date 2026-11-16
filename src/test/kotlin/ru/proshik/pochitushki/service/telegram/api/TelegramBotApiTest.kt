package ru.proshik.pochitushki.service.telegram.api

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import ru.proshik.pochitushki.BaseIntegrationTest

@ExtendWith(OutputCaptureExtension::class)
class TelegramBotApiTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var api: TelegramBotApi

    // Profile "test" sets telegram.token=some_token.
    private val base = "/botsome_token"

    @AfterEach
    fun tearDown() {
        wireMockTelegramApi.resetAll()
    }

    private fun stub(method: String, status: Int, body: String) {
        wireMockTelegramApi.stubFor(
            post(urlEqualTo("$base/$method")).willReturn(
                aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)
            )
        )
    }

    private val okMessage = """{"ok":true,"result":{"message_id":55,"chat":{"id":100,"type":"private"},"date":0}}"""

    @Test
    fun `editHtml sends link preview options as json`() {
        stub("editMessageText", 200, okMessage)
        val keyboard = InlineKeyboard(listOf(listOf(InlineButton("✓", callbackData = "ca|u:0:1:u"))))

        val ok = api.editHtml(100, 77, "<a href=\"https://p.com\">P</a>", LinkPreview("https://p.com"), keyboard)

        assertTrue(ok)
        val body = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("$base/editMessageText"))).single().bodyAsString
        assertTrue(body.contains("\"parse_mode\":\"HTML\""), body)
        assertTrue(body.contains("\"link_preview_options\":{\"url\":\"https://p.com\",\"prefer_large_media\":true}"), body)
        assertTrue(body.contains("\"message_id\":77"), body)
        assertTrue(body.contains("\"reply_markup\":{\"inline_keyboard\":[[{\"text\":\"✓\",\"callback_data\":\"ca|u:0:1:u\"}]]}"), body)
        assertFalse(body.contains("null"), "no null fields on the wire: $body")
    }

    @Test
    fun `not modified counts as success`() {
        stub(
            "editMessageText", 400,
            """{"ok":false,"error_code":400,"description":"Bad Request: message is not modified: specified new message content and reply markup are exactly the same"}""",
        )

        assertTrue(api.editHtml(100, 77, "same", null, null))
    }

    @Test
    fun `not modified keyboard counts as success`() {
        stub("editMessageReplyMarkup", 400, """{"ok":false,"error_code":400,"description":"Bad Request: message is not modified"}""")

        assertTrue(api.editKeyboard(100, 77, null))
    }

    @Test
    fun `api error is logged without the token`(output: CapturedOutput) {
        stub("editMessageText", 403, """{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""")

        assertFalse(api.editHtml(100, 77, "x", null, null))
        // Only our logger's lines: WireMock prints its own near-miss reports with stub paths.
        val ours = output.out.lines().filter { "[TelegramBotApi]" in it }
        assertTrue(ours.any { "bot was blocked by the user" in it }, "description is logged: $ours")
        assertFalse(ours.any { "some_token" in it }, "the bot token must not reach the log: $ours")
    }

    @Test
    fun `sendHtml returns message id`() {
        stub("sendMessage", 200, okMessage)

        assertEquals(55L, api.sendHtml(100, "hi", null, null, replyTo = 9))
        val body = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("$base/sendMessage"))).single().bodyAsString
        assertTrue(body.contains("\"reply_parameters\":{\"message_id\":9}"), body)
    }

    @Test
    fun `sendWidget asks for queries`() {
        stub("sendWidget", 200, okMessage)
        val keyboard = InlineKeyboard(listOf(listOf(InlineButton("📄", callbackData = "cp|U:0:1:u"))))

        assertEquals(55L, api.sendWidget(100, "<p>w</p>", "{\"r\":\"U:0:1:u\"}", keyboard, replyTo = 9))
        val body = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("$base/sendWidget"))).single().bodyAsString
        assertTrue(body.contains("\"allow_queries\":true"), body)
        assertTrue(body.contains("\"html\":\"<p>w</p>\""), body)
        assertTrue(body.contains("\"chat_data\":\"{\\\"r\\\":\\\"U:0:1:u\\\"}\""), body)
        assertTrue(body.contains("\"reply_parameters\":{\"message_id\":9}"), body)
    }

    @Test
    fun `sendWidget refusal returns null`() {
        stub("sendWidget", 400, """{"ok":false,"error_code":400,"description":"Bad Request: WIDGETS_NOT_ALLOWED"}""")

        assertNull(api.sendWidget(100, "<p>w</p>", "{}", null))
    }

    @Test
    fun `editWidgetData and answerWidgetQuery`() {
        stub("editMessageWidgetData", 200, """{"ok":true,"result":true}""")
        stub("answerWidgetQuery", 200, """{"ok":true,"result":true}""")

        assertTrue(api.editWidgetData(100, 88, "{}"))
        assertTrue(api.answerWidgetQuery("q1", null))
        val edit = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("$base/editMessageWidgetData"))).single().bodyAsString
        assertTrue(edit.contains("\"message_id\":88") && edit.contains("\"chat_data\":\"{}\""), edit)
        val answer = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("$base/answerWidgetQuery"))).single().bodyAsString
        assertEquals("{\"widget_query_id\":\"q1\"}", answer)
    }

    @Test
    fun `unreachable api returns null instead of throwing`() {
        // No stub: WireMock answers 404.
        assertNull(api.sendHtml(100, "hi", null, null))
    }
}
