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
)