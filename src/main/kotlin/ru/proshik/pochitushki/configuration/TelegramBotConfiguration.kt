package ru.proshik.pochitushki.configuration

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.logging.LogLevel
import com.github.kotlintelegrambot.webhook
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.controller.TelegramController
import ru.proshik.pochitushki.service.metrics.AppMetrics
import ru.proshik.pochitushki.service.telegram.MiniAppLinks
import ru.proshik.pochitushki.service.telegram.TelegramUpdateHandler
import ru.proshik.pochitushki.service.telegram.api.TelegramBotApi
import ru.proshik.pochitushki.service.telegram.api.TelegramErrorInterceptor
import ru.proshik.pochitushki.service.telegram.widget.WidgetMode


@Configuration
@EnableConfigurationProperties(value = [TelegramProperties::class])
class TelegramBotConfiguration(
    private val telegramProperties: TelegramProperties,
    private val botProvider: BotProvider,
    private val telegramUpdateHandlers: List<TelegramUpdateHandler>,
    private val miniAppLinks: MiniAppLinks,
    private val widgetMode: WidgetMode,
    private val telegramBotApi: TelegramBotApi,
    private val metrics: AppMetrics,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * `destroyMethod = ""`: Spring would otherwise infer one — `Bot` has a public `close()`, and
     * that is the Bot API `close` method, sent to Telegram on every shutdown. Under Krill's
     * `start-first` it would land on the bot the new container has just set up.
     *
     * Nothing else stops the bot either, on purpose. `stopWebhook()` sends `deleteWebhook` and
     * would unset the new container's webhook; `stopPolling()` in 10.0.0 dies with
     * NoSuchMethodError (`Job.cancel$default`) against the coroutines version we run. Shutdown
     * is followed by process exit, and that ends polling and the dispatcher.
     */
    @Bean(destroyMethod = "")
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
        // A URL that does not end in the controller's path leaves the bot deaf with a healthy
        // container: Telegram gets 404s and the old container is already gone. Refuse instead,
        // and the start-first rollout keeps the old one. Not echoed: an old-style URL carries the token.
        check(!webhookMode || webhookPath(telegramProperties.webhookUrl!!) == TelegramController.WEBHOOK_PATH) {
            "telegram.webhook-url must be https://<host>${TelegramController.WEBHOOK_PATH} (set TELEGRAM_WEBHOOK_URL)"
        }

        logger.info("initializing telegram bot")
        val bot = bot {
            token = telegramProperties.token
            apiUrl = telegramProperties.apiUrl
            logLevel = LogLevel.Error
            // Most library calls drop their error result; the HTTP client sees every one of them.
            httpClientInterceptors = listOf(TelegramErrorInterceptor(metrics))
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
                    // Registered by registerWebhook() below, not by the library: the library sends
                    // allowed_updates as repeated form fields, which Telegram does not parse — it then
                    // keeps whatever filter the bot had before, and widget taps may never arrive.
                    createOnStart = false
                }
            }
        }

        if (webhookMode) {
            // Telegram echoes the secret back in the X-Telegram-Bot-Api-Secret-Token header so the
            // webhook endpoint can authenticate that updates really come from Telegram.
            check(
                telegramBotApi.setWebhook(
                    url = telegramProperties.webhookUrl!!,
                    allowedUpdates = allowedUpdates(widgetMode.active),
                    secretToken = telegramProperties.webhookSecret,
                )
            ) { "telegram setWebhook failed — refusing to start in webhook mode" }
            // A failed setWebhook must stop the startup: the update channel is unbuffered, and with
            // no dispatcher every incoming webhook request would block forever on send. With
            // createOnStart=false startWebhook() only starts the dispatcher; its result is still checked.
            check(bot.startWebhook()) { "telegram dispatcher did not start — refusing to start in webhook mode" }
            logger.info("telegram bot started in webhook mode")
        } else {
            bot.startPolling()
            logger.info("telegram bot started in polling mode")
        }

        // Set the bot instance in the provider
        botProvider.setBot(bot)

        miniAppLinks.installMenuButton(bot)

        return bot
    }

    companion object {
        private fun webhookPath(url: String): String? = try {
            URI(url).path?.trimEnd('/')
        } catch (_: Exception) {
            null
        }

        /**
         * Without "callback_query" Telegram delivers only messages, and every inline button on
         * a card goes nowhere; without the two widget types widget taps never arrive. Polling
         * has no such filter, which is why this only ever breaks in production.
         */
        fun allowedUpdates(widgetsActive: Boolean): List<String> =
            listOf("message", "callback_query") +
                (if (widgetsActive) listOf("widget_query", "shared_widget_query") else emptyList())
    }
}
