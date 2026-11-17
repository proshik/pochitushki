package ru.proshik.pochitushki.controller

import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.configuration.properties.CookieProperties
import ru.proshik.pochitushki.configuration.properties.JwtProperties

/**
 * The service's cookies, one way for every login path (OIDC and the Mini App).
 *
 * SameSite=Lax is the CSRF defense for cookie-based auth: the browser won't attach these
 * cookies to cross-site state-changing requests (e.g. an attacker's auto-submitted form).
 */
@Component
@EnableConfigurationProperties(CookieProperties::class)
class AuthCookies(
    private val jwtProperties: JwtProperties,
    private val cookieProperties: CookieProperties,
) {

    fun base(name: String, value: String, maxAgeSeconds: Long): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookieProperties.secure)
            .path("/")
            .maxAge(maxAgeSeconds)
            .sameSite("Lax")
            .build()

    // Keep the cookie lifetime in sync with the JWT's own TTL so they never drift apart.
    fun auth(jwt: String): ResponseCookie = base(AUTH_TOKEN, jwt, jwtProperties.ttlDays * 24 * 60 * 60)

    fun add(response: HttpServletResponse, cookie: ResponseCookie) =
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())

    fun clear(response: HttpServletResponse, name: String) = add(response, base(name, "", 0))

    companion object {
        const val AUTH_TOKEN = "auth_token"
    }
}
