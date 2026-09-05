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
        val userId = token?.let { jwtService.extractUserId(it) }

        if (userId == null) {
            if (request.requestURI.startsWith("/api/")) {
                response.status = HttpStatus.UNAUTHORIZED.value()
            } else {
                response.sendRedirect("/login")
            }
            return false
        }

        request.setAttribute("userId", userId)

        // One user lookup per request already happens here for the locale; the
        // cover preference rides along rather than costing every controller
        // that renders a card its own query.
        val settings = try {
            userService.getUserByUserId(userId).settings
        } catch (e: DataAccessException) {
            logger.warn("Failed to load settings for userId={}, falling back to defaults", userId, e)
            null
        }
        request.setAttribute("_userLocale", Locale(settings?.languageCode ?: "ru"))
        request.setAttribute(SHOW_OG_COVERS, settings?.showOgCovers ?: true)

        return true
    }

    companion object {
        /** Request attribute carrying UserSettingsData.showOgCovers to the controllers. */
        const val SHOW_OG_COVERS = "showOgCovers"
    }
}
