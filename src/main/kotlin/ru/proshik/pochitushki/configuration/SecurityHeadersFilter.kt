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
 */
@Component
class SecurityHeadersFilter : OncePerRequestFilter() {

    private val contentSecurityPolicy = listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self'",
        "font-src 'self'",
        "connect-src 'self'",
        "form-action 'self'",
        "base-uri 'none'",
        "object-src 'none'",
        "frame-ancestors 'none'",
    ).joinToString("; ")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
        response.setHeader("Content-Security-Policy", contentSecurityPolicy)
        filterChain.doFilter(request, response)
    }
}
