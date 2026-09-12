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

        val webhookMode = !telegramProperties.webhookUrl.isNullOrBlank()
        // The webhook path is the bot token itself, and a token leaks easily (proxy access logs,
        // APM, error reports). The header secret is what actually authenticates Telegram, so in
        // webhook mode it is mandatory rather than "recommended in production".
        check(!webhookMode || !telegramProperties.webhookSecret.isNullOrBlank()) {
            "telegram.webhook-secret is required in webhook mode (set TELEGRAM_WEBHOOK_SECRET)"
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

            if (webhookMode) {
                webhook {
                    url = telegramProperties.webhookUrl
                    // Without "callback_query" Telegram delivers only messages, and every inline
                    // button on a card (archive, delete, favourite, labels, PDF, paging) goes
                    // nowhere. Polling has no such filter, which is why this only broke in prod.
                    allowedUpdates = listOf("message", "callback_query")
                    // Telegram echoes this back in the X-Telegram-Bot-Api-Secret-Token header
                    // so the webhook endpoint can authenticate that updates really come from Telegram.
                    secretToken = telegramProperties.webhookSecret
                }
            }
        }

        if (webhookMode) {
            // startWebhook() returns false when setWebhook failed. The library only starts its
            // dispatcher on success, and the update channel is unbuffered — so every incoming
            // webhook request would then block forever on send and the bot would be silently
            // dead until a restart. Fail the startup instead.
            check(bot.startWebhook()) { "telegram setWebhook failed — refusing to start in webhook mode" }
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
