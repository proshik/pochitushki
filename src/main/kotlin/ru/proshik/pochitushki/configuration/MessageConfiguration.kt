package ru.proshik.pochitushki.configuration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.web.servlet.LocaleResolver
import java.util.Locale

@Configuration
class MessageConfiguration {

    @Bean
    fun messageSource(): MessageSource {
        val source = ResourceBundleMessageSource()
        source.setBasenames("messages/messages")
        source.setDefaultEncoding("UTF-8")
        return source
    }

    /**
     * Custom locale resolver that reads the locale set by JwtAuthInterceptor
     * from the request attribute "_userLocale". Falls back to Russian when
     * the attribute is absent (unauthenticated pages like /login).
     *
     * Thymeleaf resolves #{...} messages via RequestContextUtils.getLocale(request)
     * which calls this resolver — LocaleContextHolder is NOT used for rendering.
     */
    @Bean
    fun localeResolver(): LocaleResolver = object : LocaleResolver {
        override fun resolveLocale(request: HttpServletRequest): Locale =
            (request.getAttribute("_userLocale") as? Locale) ?: Locale("ru")

        override fun setLocale(request: HttpServletRequest, response: HttpServletResponse?, locale: Locale?) {
            // locale is controlled via user settings, not by framework calls
        }
    }
}