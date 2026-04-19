package ru.proshik.pochitushki.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("telegram.oauth")
data class TelegramOAuthProperties(
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
)
