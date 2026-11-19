package ru.proshik.pochitushki.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("telegram")
data class TelegramProperties(
    /**
     * Выполнять подключение к Telegram
     */
    val enabled: Boolean,
    /**
     * Токен к боту. Обязателен только когда бот включён (enabled=true); при выключенном боте
     * можно не задавать, чтобы приложение стартовало без Telegram-конфигурации.
     */
    val token: String = "",
    /**
     * Имя токена, для отображения на странице куда редиректит Strava
     */
    val tokenName: String = "",
    /**
     * Базовый url сервиса
     */
    val baseUrl: String = "",
    /**
     * Адрес webhook, одновременно включает этот режим: `https://<домен>/telegram/webhook`
     * (TelegramController.WEBHOOK_PATH). Токена в нём нет.
     */
    val webhookUrl: String? = null,
    /**
     * Base URL для Telegram Bot API. Оставляем дефолт, но переопределяется в тестах для подмены через WireMock.
     */
    val apiUrl: String = "https://api.telegram.org/",
    /**
     * Секрет, который Telegram присылает в заголовке X-Telegram-Bot-Api-Secret-Token при вызове webhook.
     * Единственная проверка эндпоинта: запросы без совпадающего заголовка отклоняются, а при пустом
     * секрете — все. В режиме webhook обязателен (иначе приложение не стартует).
     */
    val webhookSecret: String? = null,
    /**
     * Адрес Mini App (`${APP_BASE_URL}/tg`) для кнопок «Открыть в приложении» и кнопки меню.
     * Кнопки ставятся только для https: web_app с http Telegram отвергает вместе со всем сообщением.
     */
    val miniAppUrl: String? = null,
    /** HTML-виджеты Bot API 10.4 вместо текстовой карточки (см. WidgetMode). */
    val widgets: Widgets = Widgets(),
) {
    data class Widgets(
        /** `TELEGRAM_WIDGETS_ENABLED`. Действует только в режиме webhook. По умолчанию выключено. */
        val enabled: Boolean = false,
    )
}