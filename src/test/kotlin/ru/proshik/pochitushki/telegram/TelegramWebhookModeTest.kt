package ru.proshik.pochitushki.telegram

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicLong
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.PochitushkiApplication

/**
 * The bot in webhook mode, the way production runs it: a real context (not the test-slice one, so
 * the real Prometheus registry and shutdown path), a realistic token with a colon, Bot API stubbed.
 *
 * Krill rolls out `start-first`: the new container sets the webhook, then the old one gets SIGTERM.
 * Whatever the old one sends to Telegram on the way down lands on the bot the new one just set up.
 */
class TelegramWebhookModeTest {

    private val wireMock = BaseIntegrationTest.wireMockTelegramApi

    private fun json(body: String, status: Int = 200) =
        aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

    private fun webhookInfo(url: String, pending: Int = 0, lastErrorDate: Long? = null) {
        val error = lastErrorDate?.let { ""","last_error_date":$it,"last_error_message":"Wrong response from the webhook: 502 Bad Gateway"""" } ?: ""
        wireMock.stubFor(
            get(urlPathMatching("/bot[^/]+/getWebhookInfo"))
                .willReturn(json("""{"ok":true,"result":{"url":"$url","has_custom_certificate":false,"pending_update_count":$pending$error}}"""))
        )
    }

    @BeforeEach
    fun stubs() {
        wireMock.resetAll()
        wireMock.stubFor(post(anyUrl()).willReturn(json("""{"ok":true,"result":true}""")))
        wireMock.stubFor(get(anyUrl()).willReturn(json("""{"ok":true,"result":[]}""")))
    }

    @AfterEach
    fun tearDown() {
        wireMock.resetAll()
    }

    /**
     * Polling contexts get their own token: the library cannot stop polling (see
     * TelegramBotConfiguration), so a closed polling context keeps calling getUpdates until the
     * test JVM exits, and its calls must not count against a webhook context.
     */
    private fun startPolling(vararg extra: String) = start("telegram.token=$POLLING_TOKEN", *extra)

    private fun start(vararg extra: String): ConfigurableApplicationContext {
        val pg = BaseIntegrationTest.postgresContainer
        val oidc = "http://localhost:${BaseIntegrationTest.wireMockOidc.port()}"
        // Command-line arguments, not builder properties: those are defaults, and the profile
        // files would override them.
        val args = listOf(
            "spring.datasource.url=${pg.jdbcUrl}",
            "spring.datasource.username=${pg.username}",
            "spring.datasource.password=${pg.password}",
            "telegram.api-url=http://localhost:${wireMock.port()}/",
            "telegram.oauth.base-url=$oidc",
            "telegram.oauth.expected-issuer=$oidc",
            "telegram.webhook-check.initial-delay=PT0S",
            "telegram.webhook-check.interval=PT0.2S",
            "server.port=0",
            "management.endpoints.web.exposure.include=health,prometheus",
        ) + extra +
            // A repeated --key is read as "a,b", so the default token goes in only when not given.
            listOf("telegram.token=$TOKEN").filter { extra.none { it.startsWith("telegram.token=") } }
        return SpringApplicationBuilder(PochitushkiApplication::class.java)
            .profiles("test", "telegram-test")
            .run(*args.map { "--$it" }.toTypedArray())
    }

    private fun startWebhook(vararg extra: String) = start(
        "telegram.webhook-url=$WEBHOOK_URL",
        "telegram.webhook-secret=$SECRET",
        *extra,
    )

    private fun calls(method: String, token: String = TOKEN) =
        wireMock.findAll(anyRequestedFor(urlPathMatching("/bot${Regex.escape(token)}/$method")))

    private fun port(ctx: ConfigurableApplicationContext) = ctx.environment.getProperty("local.server.port")!!

    private val http = HttpClient.newHttpClient()

    private fun postUpdate(ctx: ConfigurableApplicationContext, secret: String?, update: String = MESSAGE): Int {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:${port(ctx)}/telegram/webhook"))
            .header("Content-Type", "application/json")
            .apply { secret?.let { header("X-Telegram-Bot-Api-Secret-Token", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(update))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun scrape(ctx: ConfigurableApplicationContext): String = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:${port(ctx)}/actuator/prometheus")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    /** The value of one series, by its full name with labels as Prometheus prints them. */
    private fun value(ctx: ConfigurableApplicationContext, series: String): Double? =
        scrape(ctx).lines().firstOrNull { it.startsWith("$series ") }?.substringAfterLast(' ')?.toDouble()

    private fun awaitValue(ctx: ConfigurableApplicationContext, series: String, expected: Double) =
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertEquals(expected, value(ctx, series), series) }

    private var updateIds = 1

    private fun text(chatId: Long, text: String): String {
        val command = if (text.startsWith("/")) ""","entities":[{"type":"bot_command","offset":0,"length":${text.length}}]""" else ""
        return """{"update_id":${updateIds++},"message":{"message_id":1,"from":{"id":$chatId,"first_name":"T","is_bot":false},""" +
            """"chat":{"id":$chatId,"type":"private"},"date":0,"text":"$text"$command}}"""
    }

    @Test
    fun `stopping in webhook mode sends nothing that unsets the bot`() {
        startWebhook().use {
            assertEquals(1, calls("setWebhook").size, "startup sets the webhook")
            assertTrue(calls("setWebhook").single().bodyAsString.contains("\"url\":\"$WEBHOOK_URL\""))
        }

        assertTrue(calls("deleteWebhook").isEmpty(), "shutdown must not delete the webhook")
        // Bot.close() is the Bot API `close` method, and Spring calls a public close() on a bean
        // by itself when the context shuts down.
        assertTrue(calls("close").isEmpty(), "shutdown must not call Bot API close")
        assertTrue(calls("getUpdates").isEmpty(), "no polling in webhook mode")
    }

    @Test
    fun `stopping in polling mode does not call Bot API close`() {
        startPolling().use {
            await().atMost(Duration.ofSeconds(5)).until { calls("getUpdates", POLLING_TOKEN).isNotEmpty() }
        }

        assertTrue(calls("close", POLLING_TOKEN).isEmpty(), "shutdown must not call Bot API close")
        assertTrue(calls("deleteWebhook", POLLING_TOKEN).isEmpty())
    }

    @Test
    fun `webhook URL that is not the webhook path refuses to start, without echoing the token`() {
        val e = assertThrows(Exception::class.java) {
            start("telegram.webhook-url=https://example.test/$TOKEN", "telegram.webhook-secret=$SECRET").close()
        }
        val messages = generateSequence(e as Throwable) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { it.contains("/telegram/webhook") }, messages.toString())
        assertTrue(messages.none { it.contains(TOKEN_SECRET_PART) }, "the token is echoed in the error")
        assertTrue(calls("setWebhook").isEmpty(), "nothing registered with a wrong URL")
    }

    @Test
    fun `webhook checks the secret, and the token stays out of metrics`() {
        startWebhook().use { ctx ->
            assertEquals(403, postUpdate(ctx, secret = null), "no secret header")
            assertEquals(403, postUpdate(ctx, secret = "wrong"), "wrong secret header")
            assertEquals(200, postUpdate(ctx, secret = SECRET), "Telegram's call")

            val metrics = scrape(ctx)
            assertTrue(metrics.contains("""uri="/telegram/webhook""""), "the webhook calls are measured")
            val leaked = metrics.lines().filter { it.contains(TOKEN_SECRET_PART) }
            assertTrue(leaked.isEmpty()) {
                "the bot token leaked into metrics:\n" + leaked.joinToString("\n") { it.replace(TOKEN_SECRET_PART, "<token>") }
            }
        }
    }

    /** A body of [size] bytes that counts how much of it the server has actually pulled. */
    private class CountingBody(private val size: Long, val sent: AtomicLong = AtomicLong()) : HttpRequest.BodyPublisher {
        override fun contentLength() = size
        override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
            subscriber.onSubscribe(object : Flow.Subscription {
                @Volatile var done = false
                override fun request(n: Long) {
                    var left = n
                    while (left-- > 0 && !done) {
                        val chunk = minOf(64L * 1024, size - sent.get()).toInt()
                        if (chunk <= 0) { done = true; subscriber.onComplete(); return }
                        sent.addAndGet(chunk.toLong())
                        subscriber.onNext(ByteBuffer.wrap(ByteArray(chunk) { 'a'.code.toByte() }))
                    }
                }
                override fun cancel() { done = true }
            })
        }
    }

    private fun postLarge(ctx: ConfigurableApplicationContext, secret: String?, body: CountingBody): Int {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:${port(ctx)}/telegram/webhook"))
            .header("Content-Type", "application/json")
            .apply { secret?.let { header("X-Telegram-Bot-Api-Secret-Token", it) } }
            .expectContinue(true)
            .POST(body)
            .build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: java.io.IOException) {
            -1 // the server may close the connection on an unread body; the status is not the point then
        }
    }

    @Test
    fun `the public webhook path does not take a body before the secret is checked`() {
        startWebhook().use { ctx ->
            // Tomcat lets the client upload in parallel with handling, so some of the body goes into
            // socket buffers before the 403; reading it all (what @RequestBody did) takes the whole
            // 50 MB. Tomcat answers 403 and may drop the connection over the unread body: either is
            // a refusal.
            val unauthenticated = CountingBody(50L * 1024 * 1024)
            val refused = postLarge(ctx, secret = null, unauthenticated)
            assertTrue(refused == 403 || refused == -1) { "unauthenticated upload got $refused" }
            assertTrue(unauthenticated.sent.get() < 10L * 1024 * 1024) { "server read ${unauthenticated.sent.get()} bytes of an unauthenticated body" }

            // Telegram's updates are kilobytes; a huge body is refused even with the secret.
            val huge = CountingBody(5L * 1024 * 1024)
            val status = postLarge(ctx, secret = SECRET, huge)
            assertTrue(status == 413 || status == -1) { "oversized update got $status" }
        }
    }

    @Test
    fun `webhook URL with a trailing slash, an encoded slash or plain http refuses to start`() {
        for (url in listOf(
            "https://example.test/telegram/webhook/",
            "https://example.test/telegram%2Fwebhook",
            "http://example.test/telegram/webhook",
        )) {
            assertThrows(Exception::class.java, { start("telegram.webhook-url=$url", "telegram.webhook-secret=$SECRET").close() }, url)
        }
        assertTrue(calls("setWebhook").isEmpty())
    }

    @Test
    fun `a non-web link is a refusal, not a failure`() {
        startWebhook().use { ctx ->
            assertEquals(200, postUpdate(ctx, SECRET, text(300, "/start")))
            await().atMost(Duration.ofSeconds(5)).until { calls("sendMessage").size >= 2 }
            wireMock.resetRequests()
            assertEquals(200, postUpdate(ctx, SECRET, text(300, "doi:10.1000/xyz")))
            await().atMost(Duration.ofSeconds(5)).until { calls("sendMessage").isNotEmpty() }
            assertEquals(0.0, value(ctx, """pochitushki_operation_errors_total{operation="save_post",reason="error"}"""))
            assertEquals(0.0, value(ctx, """pochitushki_operation_errors_total{operation="bot_command",reason="error"}"""))
        }
    }

    @Test
    fun `an export Telegram refuses to take counts as a failed export`() {
        wireMock.stubFor(
            post(urlPathMatching("/bot[^/]+/sendDocument"))
                .willReturn(json("""{"ok":false,"error_code":413,"description":"Request Entity Too Large"}""", status = 413))
        )
        startWebhook().use { ctx ->
            val jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate::class.java)
            val userId = jdbc.queryForObject(
                """INSERT INTO users(telegram_id, username, first_name, settings)
                   VALUES (301, 'exp', 'E', '{"languageCode":"en"}'::jsonb) RETURNING id""",
                Long::class.java,
            )!!
            try {
                jdbc.update("INSERT INTO post (title, url, user_id) VALUES ('E', 'https://example.com/e', ?)", userId)
                ctx.getBean(ru.proshik.pochitushki.service.TelegramService::class.java).export(301, 1)

                awaitValue(ctx, """pochitushki_operation_errors_total{operation="export",reason="error"}""", 1.0)
                // The user is told, instead of waiting for a file that never comes.
                await().atMost(Duration.ofSeconds(5)).until { calls("sendMessage").isNotEmpty() }
            } finally {
                jdbc.update("DELETE FROM post WHERE user_id = ?", userId)
                jdbc.update("DELETE FROM users WHERE id = ?", userId)
            }
        }
    }

    @Test
    fun `in polling mode the webhook endpoint refuses everything`() {
        // No secret configured: the fixed public path must not accept updates in anyone's name.
        startPolling("telegram.webhook-secret=").use { ctx ->
            assertEquals(403, postUpdate(ctx, secret = null))
            assertEquals(403, postUpdate(ctx, secret = ""))
        }
    }

    @Test
    fun `webhook state is published from getWebhookInfo`() {
        webhookInfo(WEBHOOK_URL, pending = 3, lastErrorDate = 1_763_000_000)
        startWebhook().use { ctx ->
            awaitValue(ctx, "pochitushki_telegram_webhook_ours", 1.0)
            assertEquals(1.0, value(ctx, "pochitushki_telegram_webhook_mode"))
            assertEquals(3.0, value(ctx, "pochitushki_telegram_webhook_pending_updates"))
            assertEquals(1.763E9, value(ctx, "pochitushki_telegram_webhook_last_error_timestamp_seconds"))
            assertEquals(0.0, value(ctx, "pochitushki_telegram_webhook_check_failures_total"))

            // Someone else's webhook (or a deleted one) is not ours.
            webhookInfo("https://elsewhere.test/hook")
            awaitValue(ctx, "pochitushki_telegram_webhook_ours", 0.0)
            assertEquals(0.0, value(ctx, "pochitushki_telegram_webhook_last_error_timestamp_seconds"))

            // Telegram does not answer: the check fails, the last known state stays.
            wireMock.stubFor(get(urlPathMatching("/bot[^/]+/getWebhookInfo")).willReturn(json("""{"ok":false}""", status = 502)))
            await().atMost(Duration.ofSeconds(10)).until {
                (value(ctx, "pochitushki_telegram_webhook_check_failures_total") ?: 0.0) >= 1.0
            }
            assertEquals(0.0, value(ctx, "pochitushki_telegram_webhook_ours"))
            assertTrue((value(ctx, """pochitushki_telegram_errors_total{code="502",method="getWebhookInfo"}""") ?: 0.0) >= 1.0)
        }
    }

    @Test
    fun `in polling mode no webhook is ours, any webhook is not`() {
        webhookInfo("")
        startPolling().use { ctx ->
            awaitValue(ctx, "pochitushki_telegram_webhook_ours", 1.0)
            assertEquals(0.0, value(ctx, "pochitushki_telegram_webhook_mode"))
            webhookInfo(WEBHOOK_URL)
            awaitValue(ctx, "pochitushki_telegram_webhook_ours", 0.0)
        }
    }

    @Test
    fun `failed Bot API calls of the library are counted by method and status`() {
        // The reply to /start goes through the library, not the Feign client.
        wireMock.stubFor(
            post(urlPathMatching("/bot[^/]+/sendMessage"))
                .willReturn(json("""{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""", status = 403))
        )
        wireMock.stubFor(
            post(urlPathMatching("/bot[^/]+/editMessageText"))
                .willReturn(json("""{"ok":false,"error_code":400,"description":"Bad Request: message is not modified"}""", status = 400))
        )
        startWebhook().use { ctx ->
            assertEquals(200, postUpdate(ctx, SECRET, text(300, "/start")))
            // /start answers with two messages.
            awaitValue(ctx, """pochitushki_telegram_errors_total{code="403",method="sendMessage"}""", 2.0)
            assertFalse(scrape(ctx).contains("method=\"editMessageText\""), "not modified is not an error")
        }
    }

    @Test
    fun `failed user operations are counted, and exist at zero before the first failure`() {
        startWebhook().use { ctx ->
            for ((operation, reasons) in listOf(
                "save_post" to listOf("error"), "bot_command" to listOf("error"), "card_action" to listOf("error"),
                "pdf" to listOf("error", "busy"), "import" to listOf("error", "busy"), "export" to listOf("error", "busy"),
            )) for (reason in reasons) {
                assertEquals(0.0, value(ctx, """pochitushki_operation_errors_total{operation="$operation",reason="$reason"}"""), "$operation/$reason")
            }

            // A chat that never sent /start has no user row: saving its link fails on our side.
            assertEquals(200, postUpdate(ctx, SECRET, text(424242, "https://example.com/a")))
            awaitValue(ctx, """pochitushki_operation_errors_total{operation="save_post",reason="error"}""", 1.0)

            // One failure, one count: the error reply to it fails too (no user row), and that is not
            // a second failed operation.
            val botCommand = """pochitushki_operation_errors_total{operation="bot_command",reason="error"}"""
            Thread.sleep(300)
            assertEquals(0.0, value(ctx, botCommand))

            // Same chat, a command: the handler throws, the library would only print it to stderr.
            assertEquals(200, postUpdate(ctx, SECRET, text(424242, "/help")))
            awaitValue(ctx, botCommand, 1.0)
        }
    }

    companion object {
        /** Telegram's token shape: `<bot id>:<secret>`. */
        private const val TOKEN_SECRET_PART = "AAHtestSecretPart-x_y"
        private const val TOKEN = "123456:$TOKEN_SECRET_PART"
        private const val POLLING_TOKEN = "654321:AAHpollingOnly"
        private const val SECRET = "webhook-secret"
        private const val WEBHOOK_URL = "https://example.test/telegram/webhook"
        private const val MESSAGE = """{"update_id":100000,"message":{"message_id":1,"chat":{"id":1,"type":"private"},"date":0}}"""
    }
}
