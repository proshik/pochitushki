package ru.proshik.pochitushki.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.security.cookie")
data class CookieProperties(
    /**
     * Whether auth cookies carry the `Secure` attribute.
     *
     * Defaults to true so production cannot be degraded by a forgotten variable.
     * It has to be switchable because local development runs over plain http:
     * Chrome and Firefox accept a Secure cookie from `http://localhost` (they
     * reuse the Secure Contexts notion of a trustworthy origin for cookies too),
     * WebKit never connected the two, so Safari drops it silently — and no
     * browser accepts it from `http://192.168.x.x`, which is what breaks testing
     * from a phone.
     */
    val secure: Boolean = true,
)
