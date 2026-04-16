package ru.proshik.pochitushki.configuration

import jakarta.annotation.PostConstruct
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.proshik.pochitushki.configuration.properties.WebProperties

@Configuration
@EnableConfigurationProperties(WebProperties::class)
class WebConfig(private val webProperties: WebProperties) : WebMvcConfigurer {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun warnIfDefaultUserId() {
        if (webProperties.devUserId == 0L) {
            logger.warn("web.dev-user-id is 0 — set it to your actual DB user ID in application.yml")
        }
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(DevUserInterceptor())
            .addPathPatterns("/", "/archive", "/favorites", "/profile", "/api/v1/**")
    }

    private inner class DevUserInterceptor : HandlerInterceptor {
        override fun preHandle(
            request: HttpServletRequest,
            response: HttpServletResponse,
            handler: Any
        ): Boolean {
            if (request.getAttribute("userId") == null) {
                request.setAttribute("userId", webProperties.devUserId)
            }
            return true
        }
    }
}
