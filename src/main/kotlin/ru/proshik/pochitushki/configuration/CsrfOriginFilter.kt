package ru.proshik.pochitushki.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Second line of CSRF defence for the cookie-authenticated endpoints.
 *
 * `SameSite=Lax` on `auth_token` stops a genuinely cross-*site* POST, but "same site" is the
 * registrable domain: any sibling subdomain — the login broker, a status page, anything that
 * ever gets XSS'd or taken over — counts as same-site and its forged form would carry the
 * cookie. Same-origin is the property we actually want, so unsafe methods must present an
 * `Origin` matching this service, or a `Sec-Fetch-Site` that says same-origin.
 *
 * Requests with neither header are left alone: that is a non-browser client (curl, the
 * Telegram webhook, tests), where no ambient cookie is attached in the first place.
 */
@Component
class CsrfOriginFilter : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isCrossSite(request)) {
            logger.warn(
                "Rejected cross-site {} {} (origin={}, sec-fetch-site={})",
                request.method, request.requestURI,
                request.getHeader(ORIGIN), request.getHeader(SEC_FETCH_SITE),
            )
            response.sendError(HttpStatus.FORBIDDEN.value(), "Cross-site request")
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isCrossSite(request: HttpServletRequest): Boolean {
        if (request.method in SAFE_METHODS) return false

        request.getHeader(ORIGIN)?.let { origin ->
            return !origin.equals(expectedOrigin(request), ignoreCase = true)
        }

        // No Origin (older browsers on same-origin POSTs): fall back to the fetch metadata.
        return request.getHeader(SEC_FETCH_SITE)?.let { it !in SAME_SITE_VALUES } ?: false
    }

    /**
     * The origin as the browser sees it. Behind the proxy that terminates TLS the scheme and
     * host come from X-Forwarded-* (server.forward-headers-strategy=framework), which is why
     * this is built from the request rather than from a configured base URL.
     */
    private fun expectedOrigin(request: HttpServletRequest): String {
        val scheme = request.scheme
        val port = request.serverPort
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (defaultPort) "$scheme://${request.serverName}" else "$scheme://${request.serverName}:$port"
    }

    companion object {
        private const val ORIGIN = "Origin"
        private const val SEC_FETCH_SITE = "Sec-Fetch-Site"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
        private val SAME_SITE_VALUES = setOf("same-origin", "none")
    }
}
