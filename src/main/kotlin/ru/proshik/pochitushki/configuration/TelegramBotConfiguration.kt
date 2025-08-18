package ru.proshik.pochitushki.configuration

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.logging.LogLevel
import com.github.kotlintelegrambot.webhook
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.service.telegram.TelegramUpdateHandler

@Configuration
@EnableConfigurationProperties(value = [TelegramProperties::class])
class TelegramBotConfiguration(
    private val telegramProperties: TelegramProperties,
    private val botProvider: BotProvider,
    private val telegramUpdateHandlers: List<TelegramUpdateHandler>,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun telegramBot(): Bot {
        if (!telegramProperties.enabled) {
            logger.info("Telegram bot is disabled")
            return bot { token = "disabled" }
        }

        logger.info("Initializing Telegram bot")
        val bot = bot {
            token = telegramProperties.token
            logLevel = LogLevel.Error
            dispatch {
                // Register all handlers
                telegramUpdateHandlers.forEach { handler ->
                    handler.registerHandlers(this)
                }

                telegramError {
                    logger.warn("Telegram error: {}", error.getErrorMessage())
                }
            }

            if (telegramProperties.webhookUrl != null) {
                webhook {
                    url = telegramProperties.webhookUrl
                    allowedUpdates = listOf("message")
                }
            }
        }

        if (telegramProperties.webhookUrl != null) {
            bot.startWebhook()
            logger.info("Telegram bot started in webhook mode")
        } else {
            bot.startPolling()
            logger.info("Telegram bot started in polling mode")
        }

        // Set the bot instance in the provider
        botProvider.setBot(bot)

        return bot
    }
}
