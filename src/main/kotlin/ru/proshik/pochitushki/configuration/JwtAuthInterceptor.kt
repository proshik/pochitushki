package ru.proshik.pochitushki.configuration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.i18n.LocaleContextHolder
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

        val languageCode = try {
            userService.getUserByUserId(userId).settings.languageCode
        } catch (e: Exception) {
            "ru"
        }
        LocaleContextHolder.setLocale(Locale(languageCode))

        return true
    }
}
