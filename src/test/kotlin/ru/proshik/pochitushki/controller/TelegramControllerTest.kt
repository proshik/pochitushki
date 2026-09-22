package ru.proshik.pochitushki.controller

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.post
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import ru.proshik.pochitushki.BaseIntegrationTest

@AutoConfigureTestRestTemplate
@ActiveProfiles("test", "telegram-test")
class TelegramControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        wireMockTelegramApi.stubFor(
            post(anyUrl()).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":true,"result":{"message_id":1,"chat":{"id":100,"type":"private"},"text":"ok","date":0}}""")
            )
        )
    }

    @AfterEach
    fun tearDown() {
        wireMockTelegramApi.resetAll()
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    @Test
    fun `webhook endpoint processes valid update and returns 200`() {
        val json = """
            {
              "update_id": 1,
              "message": {
                "message_id": 1,
                "from": {"id": 200, "first_name": "Test", "username": "ctrl_test", "is_bot": false, "language_code": "en"},
                "chat": {"id": 200, "type": "private"},
                "text": "/start",
                "entities": [{"type": "bot_command", "offset": 0, "length": 6}]
              }
            }
        """.trimIndent()

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response = restTemplate.postForEntity("/test_token", HttpEntity(json, headers), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `webhook endpoint handles invalid JSON gracefully`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response = restTemplate.postForEntity("/test_token", HttpEntity("not a json", headers), String::class.java)

        // Should not return 200 OK for invalid JSON - expect 4xx or 5xx
        val status = response.statusCode.value()
        assert(status >= 400) { "Expected error status for invalid JSON but got $status" }
    }
}
