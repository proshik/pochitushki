package ru.proshik.pochitushki.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import feign.FeignException
import ru.proshik.pochitushki.configuration.properties.JwtProperties
import ru.proshik.pochitushki.service.JwtService
import ru.proshik.pochitushki.service.TelegramOidcService
import ru.proshik.pochitushki.service.UserService
import java.util.UUID

@Controller
class AuthController(
    private val telegramOidcService: TelegramOidcService,
    private val jwtService: JwtService,
    private val userService: UserService,
    private val jwtProperties: JwtProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/login")
    fun loginPage(): String = "login"

    @GetMapping("/auth/telegram")
    fun initiateOidc(response: HttpServletResponse): String {
        val state = UUID.randomUUID().toString()
        val codeVerifier = telegramOidcService.generateCodeVerifier()
        val codeChallenge = telegramOidcService.computeCodeChallenge(codeVerifier)

        addCookie(response, shortLivedCookie("oidc_state", state, 300))
        addCookie(response, shortLivedCookie("oidc_code_verifier", codeVerifier, 300))

        return "redirect:${telegramOidcService.buildAuthorizationUrl(state, codeChallenge)}"
    }

    @GetMapping("/auth/telegram/callback")
    fun handleCallback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        if (error != null) return "redirect:/login?error=cancelled"

        val savedState = request.cookies?.firstOrNull { it.name == "oidc_state" }?.value
        val codeVerifier = request.cookies?.firstOrNull { it.name == "oidc_code_verifier" }?.value

        if (savedState == null || state != savedState || code == null || codeVerifier == null) {
            return "redirect:/login?error=state"
        }

        clearCookie(response, "oidc_state")
        clearCookie(response, "oidc_code_verifier")

        return try {
            val userInfo = telegramOidcService.exchangeCode(code, codeVerifier)
            val user = userService.getOrCreateUser(
                telegramId = userInfo.id,
                firstName = userInfo.firstName,
                username = userInfo.username,
                languageCode = request.locale.language,
            )
            addCookie(response, authCookie(jwtService.createToken(user.id)))
            logger.info("User {} logged in (telegramId={})", user.id, userInfo.id)
            "redirect:/"
        } catch (e: Exception) {
            logger.warn("Login failed during OIDC callback: {}", e.message, e)
            "redirect:/login?error=server"
        }
    }

    @GetMapping("/logout")
    fun logout(response: HttpServletResponse): String {
        logger.debug("Logout endpoint called")

        clearCookie(response, "auth_token")

        return "redirect:/login"
    }

    // SameSite=Lax is the CSRF defense for cookie-based auth: the browser won't attach
    // these cookies to cross-site state-changing requests (e.g. an attacker's auto-submitted form).
    private fun baseCookie(name: String, value: String, maxAgeSeconds: Long): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(true)
            .path("/")
            .maxAge(maxAgeSeconds)
            .sameSite("Lax")
            .build()

    private fun shortLivedCookie(name: String, value: String, maxAgeSeconds: Int) =
        baseCookie(name, value, maxAgeSeconds.toLong())

    // Keep the cookie lifetime in sync with the JWT's own TTL so they never drift apart.
    private fun authCookie(jwt: String) =
        baseCookie("auth_token", jwt, jwtProperties.ttlDays * 24 * 60 * 60)

    private fun addCookie(response: HttpServletResponse, cookie: ResponseCookie) =
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())

    private fun clearCookie(response: HttpServletResponse, name: String) =
        addCookie(response, baseCookie(name, "", 0))
}
