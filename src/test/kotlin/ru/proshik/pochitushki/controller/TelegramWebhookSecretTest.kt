package ru.proshik.pochitushki.controller

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.post
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import ru.proshik.pochitushki.BaseIntegrationTest

@AutoConfigureTestRestTemplate
@ActiveProfiles("test", "telegram-test")
@TestPropertySource(properties = ["telegram.webhook-secret=topsecret"])
class TelegramWebhookSecretTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val updateJson = """
        {"update_id":1,"message":{"message_id":1,
          "from":{"id":200,"first_name":"Test","username":"sec_test","is_bot":false,"language_code":"en"},
          "chat":{"id":200,"type":"private"},"text":"/start",
          "entities":[{"type":"bot_command","offset":0,"length":6}]}}
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        wireMockTelegramApi.stubFor(
            post(anyUrl()).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":true,"result":{"message_id":1,"chat":{"id":100,"type":"private"},"text":"ok","date":0}}""")
            )
        )
    }

    @AfterEach
    fun tearDown() {
        wireMockTelegramApi.resetAll()
    }

    @Test
    fun `webhook without secret token is rejected with 403`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response = restTemplate.postForEntity("/test_token", HttpEntity(updateJson, headers), String::class.java)

        assert(response.statusCode == HttpStatus.FORBIDDEN) { "Expected 403 but got ${response.statusCode}" }
    }

    @Test
    fun `webhook with wrong secret token is rejected with 403`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Telegram-Bot-Api-Secret-Token", "wrong")
        }
        val response = restTemplate.postForEntity("/test_token", HttpEntity(updateJson, headers), String::class.java)

        assert(response.statusCode == HttpStatus.FORBIDDEN) { "Expected 403 but got ${response.statusCode}" }
    }

    @Test
    fun `webhook with correct secret token is accepted`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Telegram-Bot-Api-Secret-Token", "topsecret")
        }
        val response = restTemplate.postForEntity("/test_token", HttpEntity(updateJson, headers), String::class.java)

        assert(response.statusCode == HttpStatus.OK) { "Expected 200 but got ${response.statusCode}" }
    }
}
