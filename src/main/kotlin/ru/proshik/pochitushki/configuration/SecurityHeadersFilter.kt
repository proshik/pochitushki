package ru.proshik.pochitushki.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Adds baseline security response headers to every response.
 *
 * - X-Frame-Options / CSP frame-ancestors: clickjacking protection (the UI must not be framed).
 * - X-Content-Type-Options: stop MIME sniffing.
 * - Referrer-Policy: don't leak full URLs (which carry post URLs) to third parties.
 *
 * The CSP is intentionally limited to frame-ancestors so it does not break the inline scripts
 * and CDN assets the pages rely on; tighten it further once assets are self-hosted with nonces.
 */
@Component
class SecurityHeadersFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'")
        filterChain.doFilter(request, response)
    }
}
