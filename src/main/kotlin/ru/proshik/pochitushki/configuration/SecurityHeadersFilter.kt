package ru.proshik.pochitushki.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import ru.proshik.pochitushki.service.JwtService

/**
 * Adds baseline security response headers to every response.
 *
 * - X-Frame-Options / CSP frame-ancestors: clickjacking protection (the UI must not be framed).
 * - X-Content-Type-Options: stop MIME sniffing.
 * - Referrer-Policy: don't leak full URLs (which carry post URLs) to third parties.
 *
 * The CSP matters here because post titles and OG metadata are scraped from arbitrary
 * third-party sites. Thymeleaf's `th:text` escaping is the first line of defence against
 * injected markup; `script-src 'self'` is the second.
 *
 * Keeping `script-src 'self'` (no `'unsafe-inline'`, no `'unsafe-eval'`) constrains the
 * templates — see the notes at the top of static/js/app.js before adding markup:
 * no inline `<script>`, no `on*=""` handlers, and no htmx `hx-on` / `js:` expressions.
 *
 * One directive stays deliberately loose: `style-src` allows `'unsafe-inline'`,
 * because the templates carry inline `style=""` attributes. Style injection is far
 * less dangerous than script execution, so this is the accepted trade-off rather
 * than a nonce pipeline.
 *
 * `img-src` used to allow all of https: for hot-linked OG images. Those now go
 * through `OgImageProxyService`, so the only image origin is this one — do not
 * reopen it without moving the covers back off the proxy.
 *
 * Mini App mode is the one exception, and it is narrow: on `/tg`, and for a session whose
 * JWT carries the `tma` claim (signed, so it cannot be set by hand), `script-src` adds
 * `https://telegram.org` for telegram-web-app.js and `frame-ancestors` allows Telegram Web,
 * with no X-Frame-Options. Everywhere else the headers stay exactly as strict as before.
 */
@Component
class SecurityHeadersFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    private fun policy(scriptSrc: String, frameAncestors: String) = listOf(
        "default-src 'self'",
        "script-src $scriptSrc",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self'",
        "font-src 'self'",
        "connect-src 'self'",
        "form-action 'self'",
        "base-uri 'none'",
        "object-src 'none'",
        "frame-ancestors $frameAncestors",
    ).joinToString("; ")

    private val strictPolicy = policy("'self'", "'none'")
    private val miniAppPolicy = policy("'self' $TELEGRAM_SCRIPTS", TELEGRAM_WEB)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isMiniApp(request)) {
            response.setHeader("Content-Security-Policy", miniAppPolicy)
        } else {
            response.setHeader("X-Frame-Options", "DENY")
            response.setHeader("Content-Security-Policy", strictPolicy)
        }
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
        filterChain.doFilter(request, response)
    }

    private fun isMiniApp(request: HttpServletRequest): Boolean {
        if (request.requestURI == MINI_APP_ENTRY) return true
        val token = request.cookies?.firstOrNull { it.name == "auth_token" }?.value ?: return false
        return jwtService.parseToken(token)?.miniApp == true
    }

    companion object {
        const val MINI_APP_ENTRY = "/tg"
        private const val TELEGRAM_SCRIPTS = "https://telegram.org"
        private const val TELEGRAM_WEB = "https://web.telegram.org"
    }
}
