package ru.proshik.pochitushki.service.telegram

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.service.telegram.api.TelegramBotApi

/**
 * Asks Telegram where it delivers this bot's updates (`getWebhookInfo`) every
 * `telegram.webhook-check.interval` and publishes the answer as gauges — the alerts read them.
 * A container can be healthy while the bot is deaf: the webhook was deleted or points elsewhere,
 * or Telegram cannot reach us and updates pile up.
 *
 * Runs in polling mode too: there "ours" means no webhook is set, since a webhook set by anyone
 * else makes `getUpdates` fail and the bot goes just as deaf.
 *
 * The webhook URL is compared, never logged or put in a label.
 */
@Component
@EnableConfigurationProperties(TelegramProperties::class)
@ConditionalOnProperty(prefix = "telegram", name = ["enabled"], havingValue = "true")
class WebhookHealthCheck(
    private val properties: TelegramProperties,
    private val api: TelegramBotApi,
    registry: MeterRegistry,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val webhookMode = !properties.webhookUrl.isNullOrBlank()

    // NaN until the first successful check: "unknown" must not read as "broken" (or as "fine").
    @Volatile private var ours = Double.NaN
    @Volatile private var pendingUpdates = Double.NaN
    @Volatile private var lastErrorDate = Double.NaN

    private val checkFailures: Counter = Counter.builder("pochitushki.telegram.webhook.check.failures")
        .description("getWebhookInfo checks that got no answer from Telegram")
        .register(registry)

    init {
        Gauge.builder("pochitushki.telegram.webhook.mode", this) { if (it.webhookMode) 1.0 else 0.0 }
            .description("1 when the bot runs in webhook mode, 0 in polling mode")
            .register(registry)
        Gauge.builder("pochitushki.telegram.webhook.ours", this) { it.ours }
            .description("1 when Telegram delivers updates as this instance expects: to its webhook URL in webhook mode, with no webhook in polling mode")
            .register(registry)
        Gauge.builder("pochitushki.telegram.webhook.pending.updates", this) { it.pendingUpdates }
            .description("Updates Telegram holds for the bot, not yet delivered")
            .register(registry)
        Gauge.builder("pochitushki.telegram.webhook.last.error.timestamp", this) { it.lastErrorDate }
            .description("Unix time of Telegram's latest failed webhook delivery, 0 when there has been none")
            .baseUnit("seconds")
            .register(registry)
    }

    @Scheduled(
        initialDelayString = "\${telegram.webhook-check.initial-delay:PT30S}",
        fixedDelayString = "\${telegram.webhook-check.interval:PT5M}",
    )
    fun check() {
        val info = api.getWebhookInfo()
        if (info == null) {
            // TelegramBotApi has already logged the status; the gauges keep the last known answer.
            checkFailures.increment()
            return
        }
        val isOurs = if (webhookMode) info.url == properties.webhookUrl else info.url.isEmpty()
        if (!isOurs && ours != 0.0) {
            logger.warn(
                "telegram webhook is not ours: mode={}, webhook set={}",
                if (webhookMode) "webhook" else "polling", info.url.isNotEmpty(),
            )
        }
        val errorDate = (info.lastErrorDate ?: 0).toDouble()
        if (errorDate > 0 && errorDate != lastErrorDate) {
            // Telegram's own text ("Wrong response from the webhook: 502 Bad Gateway"); it does not
            // carry the URL.
            logger.warn("telegram webhook delivery failed: date={}, message={}", info.lastErrorDate, info.lastErrorMessage)
        }
        ours = if (isOurs) 1.0 else 0.0
        pendingUpdates = info.pendingUpdateCount.toDouble()
        lastErrorDate = errorDate
    }
}
