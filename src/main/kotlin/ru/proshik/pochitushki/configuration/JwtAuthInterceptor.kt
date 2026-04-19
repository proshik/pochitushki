package ru.proshik.pochitushki.configuration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import ru.proshik.pochitushki.service.JwtService

@Component
class JwtAuthInterceptor(private val jwtService: JwtService) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val token = request.cookies?.firstOrNull { it.name == "auth_token" }?.value
        val userId = token?.let { jwtService.extractUserId(it) }

        if (userId == null) {
            val accept = request.getHeader("Accept") ?: ""
            val isApiRequest = request.requestURI.startsWith("/api/")
            if (!isApiRequest && !accept.contains("application/json")) {
                response.sendRedirect("/login")
            } else {
                response.status = HttpStatus.UNAUTHORIZED.value()
            }
            return false
        }

        request.setAttribute("userId", userId)
        return true
    }
}
