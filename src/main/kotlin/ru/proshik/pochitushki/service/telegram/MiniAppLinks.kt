package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.MenuButton
import com.github.kotlintelegrambot.entities.keyboard.WebAppInfo
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.model.UserSettingsLimits
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.telegram.card.CardList

/**
 * Where the bot's "open in the app" buttons point: `telegram.mini-app-url` (`${APP_BASE_URL}/tg`).
 *
 * Only https counts. Telegram refuses a whole sendMessage whose keyboard carries a web_app
 * button with an http URL — locally (`http://localhost`) every reply to /start and to a saved
 * link would silently vanish — so without https there are simply no app buttons.
 */
@Component
@EnableConfigurationProperties(TelegramProperties::class)
class MiniAppLinks(
    private val properties: TelegramProperties,
    private val i18n: I18nService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** The Mini App URL opening the shelf of [list], or null when there is no https URL. */
    fun url(list: CardList? = null): String? {
        val base = properties.miniAppUrl?.trim()?.takeIf { it.startsWith("https://") } ?: return null
        val page = when (list) {
            CardList.ARCHIVE -> "archive"
            CardList.FAVORITES -> "favorites"
            CardList.RANDOM -> "random"
            else -> null
        }
        return if (page == null) base else "$base?p=$page"
    }

    /** The menu button next to the input field opens the app. A failure is logged, not fatal. */
    fun installMenuButton(bot: Bot) {
        val url = url() ?: run {
            logger.info("Mini App menu button not set: telegram.mini-app-url is not an https URL")
            return
        }
        val text = i18n.getMessage("bot.menu.app", UserSettingsLimits.LANGUAGE_RU)
        bot.setChatMenuButton(menuButton = MenuButton.WebApp(text = text, webApp = WebAppInfo(url))).fold(
            { logger.info("Mini App menu button set") },
            { logger.warn("Mini App menu button not set: {}", it.javaClass.simpleName) },
        )
    }
}
