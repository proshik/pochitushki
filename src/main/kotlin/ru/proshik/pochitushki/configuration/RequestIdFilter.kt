package ru.proshik.pochitushki.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Puts a request id in the MDC (and echoes it back) so the lines of one request can be found
 * together in the log. The pattern in logback.xml used to print `%X{transaction.id}` and
 * `%X{trace.id}`, which nothing ever populated: two empty brackets on every line.
 *
 * An id supplied by the proxy is reused, so a trace started upstream keeps the same id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER)?.take(64)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().take(8)

        MDC.put(MDC_KEY, requestId)
        response.setHeader(HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        private const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
    }
}
