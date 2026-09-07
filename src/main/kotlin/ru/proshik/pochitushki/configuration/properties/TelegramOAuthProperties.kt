package ru.proshik.pochitushki.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("telegram.oauth")
data class TelegramOAuthProperties(
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    /**
     * The exact `iss` the id_token must carry. Configuration rather than a
     * substring rule, and set separately from [baseUrl] on purpose: pointing the
     * flow at a local login broker has to be a deliberate change to both, not
     * something one edit quietly enables.
     */
    val expectedIssuer: String,
)
