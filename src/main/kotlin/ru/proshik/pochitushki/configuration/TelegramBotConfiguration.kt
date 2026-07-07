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
            logger.info("telegram bot is disabled")
            // Кладём заглушку в провайдер, чтобы случайный getBot() не падал на lateinit.
            // Polling/webhook не стартуют, обработчики не регистрируются.
            val stub = bot { token = "disabled" }
            botProvider.setBot(stub)
            return stub
        }

        logger.info("initializing telegram bot")
        val bot = bot {
            token = telegramProperties.token
            apiUrl = telegramProperties.apiUrl
            logLevel = LogLevel.Error
            dispatch {
                // Register all handlers
                telegramUpdateHandlers.forEach { handler ->
                    handler.registerHandlers(this)
                }

                telegramError {
                    logger.warn("telegram bot error: {}", error.getErrorMessage())
                }
            }

            if (!telegramProperties.webhookUrl.isNullOrBlank()) {
                webhook {
                    url = telegramProperties.webhookUrl
                    allowedUpdates = listOf("message")
                    // Telegram echoes this back in the X-Telegram-Bot-Api-Secret-Token header
                    // so the webhook endpoint can authenticate that updates really come from Telegram.
                    telegramProperties.webhookSecret?.takeIf { it.isNotBlank() }?.let { secretToken = it }
                }
            }
        }

        if (!telegramProperties.webhookUrl.isNullOrBlank()) {
            bot.startWebhook()
            logger.info("telegram bot started in webhook mode")
        } else {
            bot.startPolling()
            logger.info("telegram bot started in polling mode")
        }

        // Set the bot instance in the provider
        botProvider.setBot(bot)

        return bot
    }
}
