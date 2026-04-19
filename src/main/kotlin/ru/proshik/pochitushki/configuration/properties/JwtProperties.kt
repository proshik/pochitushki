package ru.proshik.pochitushki.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("jwt")
data class JwtProperties(
    val secret: String,
    val ttlDays: Long,
)
