package ru.proshik.pochitushki.configuration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.proshik.pochitushki.configuration.properties.WebProperties

@Configuration
@EnableConfigurationProperties(WebProperties::class)
class WebConfig(private val webProperties: WebProperties) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
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
        })
    }
}
