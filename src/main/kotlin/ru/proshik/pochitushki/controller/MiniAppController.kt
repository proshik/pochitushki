package ru.proshik.pochitushki.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.model.UserSettingsLimits
import ru.proshik.pochitushki.service.JwtService
import ru.proshik.pochitushki.service.UserService
import ru.proshik.pochitushki.service.miniapp.InitData
import ru.proshik.pochitushki.service.miniapp.InitDataException

data class WebAppLoginRequest(val initData: String = "", val page: String? = null)

data class WebAppLoginResponse(val redirect: String)

/**
 * Login inside Telegram: the site opened as a Mini App signs in by `initData` instead of the
 * OIDC screen. `/tg` is the Mini App's address in BotFather; its script posts
 * `Telegram.WebApp.initData` here and goes to the page this answers with.
 *
 * Public on purpose (not in WebConfig): `/tg` and the `/auth` paths are where a session starts.
 */
@Controller
@EnableConfigurationProperties(TelegramProperties::class)
class MiniAppController(
    private val telegramProperties: TelegramProperties,
    private val userService: UserService,
    private val jwtService: JwtService,
    private val cookies: AuthCookies,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/tg")
    fun page(): String = "tg"

    @PostMapping("/auth/telegram/webapp")
    @ResponseBody
    fun login(@RequestBody body: WebAppLoginRequest, response: HttpServletResponse): ResponseEntity<WebAppLoginResponse> {
        val token = telegramProperties.token
        if (token.isBlank()) {
            logger.warn("Mini App login refused: telegram.token is not set")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val data = try {
            InitData.validate(body.initData, token, Instant.now())
        } catch (e: InitDataException) {
            logger.info("Mini App login refused: {}", e.reason)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val user = userService.getOrCreateUser(
            telegramId = data.user.id,
            firstName = data.user.firstName,
            username = data.user.username,
            languageCode = data.user.languageCode ?: UserSettingsLimits.LANGUAGE_RU,
        )
        cookies.add(response, cookies.auth(jwtService.createToken(user.id, miniApp = true)))
        logger.info("User {} logged in via Mini App (telegramId={})", user.id, data.user.id)

        return ResponseEntity.ok(WebAppLoginResponse(redirectFor(body.page ?: data.startParam)))
    }

    /**
     * Whether the auth cookie came back. Inside Telegram Web the page runs in a cross-site
     * frame, where a SameSite=Lax cookie is neither stored nor sent; `/tg` then offers to open
     * the site in the browser instead of looping on the login page.
     */
    @GetMapping("/auth/telegram/webapp/session")
    fun session(request: HttpServletRequest): ResponseEntity<Void> {
        val token = request.cookies?.firstOrNull { it.name == AuthCookies.AUTH_TOKEN }?.value
        val valid = token?.let { jwtService.parseToken(it) } != null
        return ResponseEntity.status(if (valid) HttpStatus.NO_CONTENT else HttpStatus.UNAUTHORIZED).build()
    }

    companion object {
        /** Where a Mini App launch may land. Anything else goes to the feed: no open redirect. */
        private val PAGES = mapOf(
            "feed" to "/",
            "archive" to "/archive",
            "favorites" to "/favorites",
            "random" to "/random",
        )

        fun redirectFor(page: String?): String = PAGES[page] ?: "/"
    }
}
