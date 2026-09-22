package ru.proshik.pochitushki.controller

import tools.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.security.MessageDigest
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.configuration.properties.TelegramProperties

/**
 * Controller for handling Telegram webhook requests.
 * This class is a replacement for the original TelegramHandler that was refactored
 * to separate concerns as per the requirements.
 */
@RestController
@EnableConfigurationProperties(value = [TelegramProperties::class])
// Webhook-эндпоинт нужен только когда бот включён. При enabled=false бин не создаётся,
// поэтому маппинг "/${telegram.token}" не регистрируется (и не схлопывается в "POST /"),
// а обращение к botProvider.getBot() отсюда исключено.
@ConditionalOnProperty(prefix = "telegram", name = ["enabled"], havingValue = "true")
class TelegramController(
    private val botProvider: BotProvider,
    private val telegramProperties: TelegramProperties,
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
    @PostMapping("/\${telegram.token}")
    suspend fun processUpdate(
        @RequestBody data: String,
        @RequestHeader(value = SECRET_TOKEN_HEADER, required = false) secretToken: String?,
    ) {
        // Authenticate the caller: only Telegram, which echoes the configured secret back in
        // this header, may post updates. The secret URL path is not sufficient — it is the bot
        // token, and tokens end up in proxy logs. The comparison is constant-time so the header
        // cannot be guessed byte by byte from response timings.
        val expected = telegramProperties.webhookSecret
        if (!expected.isNullOrBlank()) {
            val provided = secretToken.orEmpty()
            if (!MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())) {
                logger.warn("Rejected webhook call with missing/invalid secret token")
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid webhook secret")
            }
        }

        val updateId = extractUpdateId(data)
        if (updateId != null && seenUpdateIds.getIfPresent(updateId) != null) {
            logger.info("Skipping redelivered Telegram update: updateId={}", updateId)
            return
        }

        botProvider.getBot().processUpdate(data)
        updateId?.let { seenUpdateIds.put(it, true) }

        // The update body carries message text, names and file ids — logging it would put user
        // content in the log. Only the id goes in.
        logger.debug("Processed Telegram update: updateId={}", updateId)
    }

    /** null when the body is not JSON or carries no update_id — such a body is not deduplicated. */
    private fun extractUpdateId(data: String): Long? = try {
        objectMapper.readTree(data).path("update_id").takeIf { it.isNumber }?.asLong()
    } catch (e: Exception) {
        null
    }

    companion object {
        const val SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token"
    }
}
