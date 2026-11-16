package ru.proshik.pochitushki.service.telegram.widget

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.configuration.properties.TelegramProperties

/**
 * Whether feed cards go out as HTML widgets (Bot API 10.4) instead of text cards.
 *
 * Needs the flag **and** webhook mode: widget taps arrive as `widget_query`, which the
 * library does not know and in polling never hands over; TelegramController picks them out
 * of the webhook body before the library sees it. With the flag on in polling the bot keeps
 * sending text cards and says so once at startup.
 */
@Component
@EnableConfigurationProperties(TelegramProperties::class)
class WidgetMode(properties: TelegramProperties) {

    val active: Boolean = properties.widgets.enabled && !properties.webhookUrl.isNullOrBlank()

    init {
        if (properties.widgets.enabled && !active) {
            LoggerFactory.getLogger(javaClass).warn(
                "telegram.widgets.enabled is set, but widgets need webhook mode — sending text cards"
            )
        }
    }
}
