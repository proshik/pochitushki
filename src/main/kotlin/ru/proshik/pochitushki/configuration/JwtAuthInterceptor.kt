package ru.proshik.pochitushki.configuration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import ru.proshik.pochitushki.service.JwtService
import ru.proshik.pochitushki.service.UserService
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@Component
class JwtAuthInterceptor(
    private val jwtService: JwtService,
    private val userService: UserService,
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val token = request.cookies?.firstOrNull { it.name == "auth_token" }?.value
        val tokenInfo = token?.let { jwtService.parseToken(it) }

        if (tokenInfo == null) {
            return reject(request, response)
        }

        val userId = tokenInfo.userId

        // One user lookup per request already happens here for the locale; the
        // cover preference rides along rather than costing every controller
        // that renders a card its own query.
        val user = try {
            userService.getUserByUserId(userId)
        } catch (e: DataAccessException) {
            logger.warn("Failed to load user {}, falling back to defaults", userId, e)
            null
        }

        // Logout stamps tokens_valid_after, so a token minted before it is dead even though its
        // signature and exp still check out. This is the revocation a stateless JWT otherwise
        // cannot have, and it costs nothing: the row is already loaded for the locale.
        if (user != null && isRevoked(tokenInfo, user.tokensValidAfter)) {
            logger.info("Refused a token issued before logout: userId={}", userId)
            return reject(request, response)
        }

        request.setAttribute("userId", userId)
        val settings = user?.settings
        request.setAttribute("_userLocale", Locale(settings?.languageCode ?: "ru"))
        request.setAttribute(SHOW_OG_COVERS, settings?.showOgCovers ?: true)

        return true
    }

    private fun isRevoked(tokenInfo: JwtService.TokenInfo, validAfter: LocalDateTime?): Boolean {
        if (validAfter == null) return false
        val issuedAt = tokenInfo.issuedAt ?: return true // no iat — cannot prove it postdates the logout
        return issuedAt.isBefore(validAfter.atZone(ZoneId.systemDefault()).toInstant())
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse): Boolean {
        if (request.requestURI.startsWith("/api/")) {
            response.status = HttpStatus.UNAUTHORIZED.value()
        } else {
            response.sendRedirect("/login")
        }
        return false
    }

    companion object {
        /** Request attribute carrying UserSettingsData.showOgCovers to the controllers. */
        const val SHOW_OG_COVERS = "showOgCovers"
    }
}
