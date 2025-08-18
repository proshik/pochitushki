package ru.proshik.pochitushki.controller

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.configuration.properties.TelegramProperties

/**
 * Controller for handling Telegram webhook requests.
 * This class is a replacement for the original TelegramHandler that was refactored
 * to separate concerns as per the requirements.
 */
@RestController
@EnableConfigurationProperties(value = [TelegramProperties::class])
class TelegramController(private val botProvider: BotProvider) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process webhook updates from Telegram.
     */
    @PostMapping("/\${telegram.token}")
    suspend fun processUpdate(@RequestBody data: String) {
        botProvider.getBot().processUpdate(data)
        logger.debug("Processed Telegram update: {}", data)
    }
}
