package ru.proshik.pochitushki.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("telegram")
data class TelegramProperties(
    /**
     * Выполнять подключение к Telegram
     */
    val enabled: Boolean,
    /**
     * Токен к боту
     */
    val token: String,
    /**
     * Имя токена, для отображения на странице куда редиректит Strava
     */
    val tokenName: String,
    /**
     * Базовый url сервиса
     */
    val baseUrl: String,
    /**
     * Установка url до сервиса для режима webhook, одновременно и включает его. В url содержится ещё и token
     */
    val webhookUrl: String? = null,
    /**
     * Base URL для Telegram Bot API. Оставляем дефолт, но переопределяется в тестах для подмены через WireMock.
     */
    val apiUrl: String = "https://api.telegram.org/",
    /**
     * Секрет, который Telegram присылает в заголовке X-Telegram-Bot-Api-Secret-Token при вызове webhook.
     * Если задан — webhook-запросы без совпадающего заголовка отклоняются. Пусто => проверка выключена.
     */
    val webhookSecret: String? = null,
)