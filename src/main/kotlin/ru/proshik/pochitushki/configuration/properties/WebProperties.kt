package ru.proshik.pochitushki.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("web")
data class WebProperties(val devUserId: Long)
