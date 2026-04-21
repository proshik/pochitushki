package ru.proshik.pochitushki.controller

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import feign.FeignException
import ru.proshik.pochitushki.service.JwtService
import ru.proshik.pochitushki.service.TelegramOidcService
import ru.proshik.pochitushki.service.UserService
import java.util.UUID

@Controller
class AuthController(
    private val telegramOidcService: TelegramOidcService,
    private val jwtService: JwtService,
    private val userService: UserService,
) {

    @GetMapping("/login")
    fun loginPage(): String = "login"

    @GetMapping("/auth/telegram")
    fun initiateOidc(response: HttpServletResponse): String {
        val state = UUID.randomUUID().toString()
        val codeVerifier = telegramOidcService.generateCodeVerifier()
        val codeChallenge = telegramOidcService.computeCodeChallenge(codeVerifier)

        response.addCookie(shortLivedCookie("oidc_state", state, 300))
        response.addCookie(shortLivedCookie("oidc_code_verifier", codeVerifier, 300))

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
            response.addCookie(authCookie(jwtService.createToken(user.id)))
            "redirect:/"
        } catch (e: Exception) {
            "redirect:/login?error=server"
        }
    }

    @GetMapping("/logout")
    fun logout(response: HttpServletResponse): String {
        clearCookie(response, "auth_token")
        return "redirect:/login"
    }

    private fun shortLivedCookie(name: String, value: String, maxAgeSeconds: Int) =
        Cookie(name, value).apply { isHttpOnly = true; secure = true; path = "/"; maxAge = maxAgeSeconds }

    private fun authCookie(jwt: String) =
        Cookie("auth_token", jwt).apply { isHttpOnly = true; secure = true; path = "/"; maxAge = 7 * 24 * 60 * 60 }

    private fun clearCookie(response: HttpServletResponse, name: String) =
        response.addCookie(Cookie(name, "").apply { isHttpOnly = true; secure = true; path = "/"; maxAge = 0 })
}
