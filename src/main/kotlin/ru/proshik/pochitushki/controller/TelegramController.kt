package ru.proshik.pochitushki.controller

import org.slf4j.LoggerFactory
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
class TelegramController(
    private val botProvider: BotProvider,
    private val telegramProperties: TelegramProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process webhook updates from Telegram.
     */
    @PostMapping("/\${telegram.token}")
    suspend fun processUpdate(
        @RequestBody data: String,
        @RequestHeader(value = SECRET_TOKEN_HEADER, required = false) secretToken: String?,
    ) {
        // Authenticate the caller: when a secret is configured, only Telegram (which echoes it
        // back in this header) may post updates. The secret URL path alone is not sufficient.
        val expected = telegramProperties.webhookSecret
        if (!expected.isNullOrBlank() && secretToken != expected) {
            logger.warn("Rejected webhook call with missing/invalid secret token")
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid webhook secret")
        }

        botProvider.getBot().processUpdate(data)

        logger.debug("Processed Telegram update: {}", data)
    }

    companion object {
        const val SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token"
    }
}
