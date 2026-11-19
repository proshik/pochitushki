package ru.proshik.pochitushki.controller

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.servlet.http.HttpServletRequest
import java.security.MessageDigest
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.service.telegram.widget.WidgetMode
import ru.proshik.pochitushki.service.telegram.widget.WidgetQueryHandler

/**
 * Controller for handling Telegram webhook requests.
 * This class is a replacement for the original TelegramHandler that was refactored
 * to separate concerns as per the requirements.
 */
@RestController
@EnableConfigurationProperties(value = [TelegramProperties::class])
// Webhook-эндпоинт нужен только когда бот включён: при enabled=false бин не создаётся, и
// обращение к botProvider.getBot() отсюда исключено.
@ConditionalOnProperty(prefix = "telegram", name = ["enabled"], havingValue = "true")
class TelegramController(
    private val botProvider: BotProvider,
    private val telegramProperties: TelegramProperties,
    private val widgetMode: WidgetMode,
    private val widgetQueryHandler: WidgetQueryHandler,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val objectMapper = ObjectMapper()

    /**
     * update_id values seen recently. Telegram redelivers an update whenever the webhook
     * call does not finish fast enough, and a redelivered /feed or import would run twice.
     * Ten minutes is well past Telegram's retry window; the cap keeps this bounded.
     */
    private val seenUpdateIds: Cache<Long, Boolean> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .maximumSize(10_000)
        .build()

    /**
     * Process webhook updates from Telegram.
     */
    @PostMapping(WEBHOOK_PATH)
    suspend fun processUpdate(
        request: HttpServletRequest,
        @RequestHeader(value = SECRET_TOKEN_HEADER, required = false) secretToken: String?,
    ) {
        // Authenticate the caller: only Telegram, which echoes the configured secret back in
        // this header, may post updates. The path is public and fixed — the header is the only
        // check, so without a configured secret (polling) every call is refused: otherwise anyone
        // could post an update in another user's name. The comparison is constant-time so the
        // header cannot be guessed byte by byte from response timings.
        //
        // The body is read by hand, after this check and with a cap: a @RequestBody would be read
        // whole, before the method runs, for anyone who knows the public path. Refusals are not
        // logged above debug for the same reason; they show in http_server_requests{status="403"}.
        val expected = telegramProperties.webhookSecret
        val provided = secretToken.orEmpty()
        if (expected.isNullOrBlank() || !MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())) {
            logger.debug("Rejected webhook call with missing/invalid secret token")
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid webhook secret")
        }
        val bytes = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) {
            logger.warn("Rejected webhook call with a body over {} bytes", MAX_BODY_BYTES)
            throw ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Update too large")
        }
        val data = String(bytes, Charsets.UTF_8)

        val update = parse(data)
        val updateId = update?.path("update_id")?.takeIf { it.isNumber }?.asLong()
        if (updateId != null && seenUpdateIds.getIfPresent(updateId) != null) {
            logger.info("Skipping redelivered Telegram update: updateId={}", updateId)
            return
        }

        // Widget taps (Bot API 10.4) are not an update type the library knows; picked out here,
        // before it would parse them into an empty Update and drop them.
        val widgetQuery = update?.get("widget_query")
        val sharedWidgetQuery = update?.get("shared_widget_query")
        when {
            widgetMode.active && widgetQuery != null -> widgetQueryHandler.handle(widgetQuery)
            widgetMode.active && sharedWidgetQuery != null -> widgetQueryHandler.handleShared(sharedWidgetQuery)
            else -> botProvider.getBot().processUpdate(data)
        }
        updateId?.let { seenUpdateIds.put(it, true) }

        // The update body carries message text, names and file ids — logging it would put user
        // content in the log. Only the id goes in.
        logger.debug("Processed Telegram update: updateId={}", updateId)
    }

    /** null when the body is not JSON — such a body is not deduplicated, and goes to the library as is. */
    private fun parse(data: String): JsonNode? = try {
        objectMapper.readTree(data)
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * A fixed path, not the bot token: a token in the path ends up in the `uri` label of
         * `http_server_requests`, in proxy access logs and in `getWebhookInfo`.
         */
        const val WEBHOOK_PATH = "/telegram/webhook"

        /** An update is a few kilobytes; a message at Telegram's 4096-character limit is far below this. */
        private const val MAX_BODY_BYTES = 1024 * 1024
        const val SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token"
    }
}
